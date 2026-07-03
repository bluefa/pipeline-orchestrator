#!/usr/bin/env node
// Mock InfraManager stub — 단일 파일, 의존성 0. 실행: node mock-infra.js  (포트: PORT env, 기본 8089)
// 오케스트레이터 연결: INFRA_MANAGER_BASE_URL=http://localhost:8089  INFRA_MANAGER_TOKEN=anything  (토큰은 무시하지만 기동엔 필요)
// 실패 토글: MOCK_INFRA_FAIL=1 (모든 job/조건 실패) 또는 targetSourceId 에 "fail" 포함 (그 job만 실패) → Task 가 FAILED 로 기록.
// 자체 점검: node mock-infra.js selftest
//
// API 계약의 진실원은 src/main/java/com/bff/pipeline/client/InfraManagerFeignClient.java 와
// dto/{DispatchedJob, TerraformJobStatusResponse} 다. 요약:
//   dispatch (POST/DELETE)      → {"id": <number>} (단건형 family) 또는 [{"id": <number>}] (목록형 family)
//   status   (GET, /result 아님) → {"terraformState": ..., "failReason": ..., "resultPath": ...}
//   result   (GET .../result)    → text/plain 본문 (Feign String 디코더)
//   terminal terraformState      → COMPLETED(plan/apply 성공) / DESTROYED(destroy 성공) / FAILED, 그 외 전부 진행중
//   condition                    → GET /infra/network/ready?target= → {"met": true}
//                                  GET /infra/targets/{t}/cloud-provider → {"provider": "AWS"}
// provider 규칙: cloud-provider 는 매 pipeline 생성 시 호출돼 어느 recipe 를 돌릴지 결정한다. target 에
// "gcp"/"azure"/"idc" 가 들어있으면 그 provider, 아니면 AWS. → target 이름만으로 provider별 E2E 를 골라 돌린다.
//
// RUNNING_POLLS 는 job당 "status 호출 횟수"지 벽시계가 아니다. 오케스트레이터는 RUNNING 폴을 pipeline.polling-interval
// (기본 PT10M) 뒤로 재스케줄하므로 task 하나의 실제 소요 ≈ RUNNING_POLLS × polling-interval 이다. 빠른 E2E 는
// polling-interval 을 짧게(예: PIPELINE_POLLING_INTERVAL=PT2S) 두고 RUNNING_POLLS 는 작게 유지한다. RUNNING_POLLS 를
// 크게 잡으면 task 가 execution-timeout(기본 PT50M) 에 먼저 걸려 FAILED 로 종결될 수 있다.
//
// 상태는 in-memory 다 — 재시작하면 job/poll 상태가 전부 초기화된다. 미발급 job id 는 실패 아닌 새 job 으로 취급하므로
// (재시작 내성) run 도중 재시작하면 fail-target 의 실패 의도가 유실된다. run 도중에는 재시작하지 않는다.
'use strict';
const http = require('http');

const PORT = Number(process.env.PORT || 8089);
// job당 RUNNING 을 돌려줄 status 호출 횟수(이후 terminal). 벽시계 환산은 위 헤더 주석 참조.
const RUNNING_POLLS = Number(process.env.MOCK_INFRA_RUNNING_POLLS || 2);
if (!Number.isInteger(RUNNING_POLLS) || RUNNING_POLLS < 0) {
    throw new Error(`MOCK_INFRA_RUNNING_POLLS must be a non-negative integer, got: ${process.env.MOCK_INFRA_RUNNING_POLLS}`);
}
const FAIL_ALL = /^(1|true|yes|on)$/i.test(process.env.MOCK_INFRA_FAIL || '');

// 단건 객체로 dispatch 응답하는 family (그 외는 전부 목록). InfraManagerFeignClient 의 반환 타입에서 도출.
// 판별은 targetSourceId 를 제외한 operation 경로에만 한다 — targetSourceId 에 이 substring 이 들어가 shape 이
// 오분류되지 않게(예: target "x-bdc-service-level-terraform-jobs" 로 AWS service dispatch).
const SINGLE_DISPATCH = ['service/level/common/terraform', 'bdc-service-level-terraform-jobs', 'idc/terraform'];

// dispatch 경로에서 targetSourceId 세그먼트를 뺀 operation 경로(.../target-sources/{id}/<여기부터>).
function operationPath(path) {
    const seg = path.split('/').filter(Boolean);
    const i = seg.indexOf('target-sources');
    return i >= 0 ? seg.slice(i + 2).join('/') : path;
}

const jobs = new Map(); // jobId(String) -> { polls, fail }
let nextJobId = 1000;

function failTarget(target) {
    return FAIL_ALL || (typeof target === 'string' && target.includes('fail'));
}

// cloudProvider 는 target 이름으로 정한다 — mock 이 provider 를 하드코딩하면 모든 recipe 가 한 provider 로 쏠린다.
function providerFor(target) {
    const t = (target || '').toLowerCase();
    if (t.includes('gcp')) return 'GCP';
    if (t.includes('azure')) return 'AZURE';
    if (t.includes('idc')) return 'IDC';
    return 'AWS';
}

// dispatch 경로의 targetSourceId — 모든 dispatch 경로는 .../target-sources/{targetSourceId}/... 꼴이다.
function targetSourceId(path) {
    const seg = path.split('/').filter(Boolean);
    const i = seg.indexOf('target-sources');
    return i >= 0 && i + 1 < seg.length ? decodeURIComponent(seg[i + 1]) : '';
}

// status/result 경로의 마지막 세그먼트가 terraformJobId 다 (result 는 호출부에서 /result 를 떼고 넘긴다).
function jobIdFrom(path) {
    const seg = path.split('/').filter(Boolean);
    return seg[seg.length - 1];
}

function dispatch(path, res) {
    const id = nextJobId++;
    jobs.set(String(id), { polls: 0, fail: failTarget(targetSourceId(path)) });
    const op = operationPath(path);
    const single = SINGLE_DISPATCH.some((s) => op.includes(s));
    json(res, single ? { id } : [{ id }]); // id 는 JSON number → Java Long
}

function statusBody(jobId, path, query) {
    let job = jobs.get(jobId);
    if (!job) { // 미발급 id(예: mock 재시작 후) 는 새 job 으로 취급
        job = { polls: 0, fail: FAIL_ALL };
        jobs.set(jobId, job);
    }
    job.polls++;
    if (job.polls <= RUNNING_POLLS) {
        return { terraformState: 'RUNNING', failReason: null, resultPath: null };
    }
    if (job.fail) {
        return { terraformState: 'FAILED', failReason: 'mock forced failure', resultPath: null };
    }
    // destroy 성공 terminal 은 DESTROYED, plan/apply 는 COMPLETED. status 요청이 스스로 destroy 임을 밝힌다.
    const isDestroy = path.includes('/destroy') || query.get('jobType') === 'DESTROY';
    const state = isDestroy ? 'DESTROYED' : 'COMPLETED';
    return { terraformState: state, failReason: null, resultPath: `s3://mock-infra/tf-${jobId}.log` };
}

function json(res, obj) {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(obj));
}

function text(res, body) {
    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end(body);
}

const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost');
    const path = url.pathname;
    const query = url.searchParams;
    req.resume(); // dispatch 는 본문이 없지만 소켓을 비운다
    console.log(`${req.method} ${req.url}`);
    try {
        if (req.method === 'POST' || req.method === 'DELETE') {
            return dispatch(path, res);
        }
        if (path.endsWith('/result')) {
            const jobId = jobIdFrom(path.slice(0, -'/result'.length));
            return text(res, `mock terraform log for job ${jobId}\n`);
        }
        if (path === '/infra/network/ready') {
            return json(res, { met: !failTarget(query.get('target')) });
        }
        if (path.endsWith('/cloud-provider')) {
            const seg = path.split('/').filter(Boolean); // /infra/targets/{target}/cloud-provider
            return json(res, { provider: providerFor(seg[seg.indexOf('targets') + 1]) });
        }
        // status: 발급 job id 는 숫자다. 마지막 세그먼트가 숫자가 아니면 미상 경로이므로 404 로 드러낸다
        // (오타/오라우팅이 그럴싸한 status 200 으로 묻히지 않게 — E2E 디버깅용).
        const jobId = jobIdFrom(path);
        if (!/^\d+$/.test(jobId)) {
            res.writeHead(404, { 'Content-Type': 'text/plain' });
            return res.end(`no route for ${req.method} ${path}\n`);
        }
        return json(res, statusBody(jobId, path, query));
    } catch (err) {
        res.writeHead(500, { 'Content-Type': 'text/plain' });
        res.end(String(err));
    }
});

if (require.main === module) {
    if (process.argv[2] === 'selftest') {
        runSelftest();
    } else {
        server.listen(PORT, () => console.log(`mock-infra 대기 중: http://localhost:${PORT}  (fail-all=${FAIL_ALL}, running-polls=${RUNNING_POLLS})`));
    }
}

// ── 자체 점검: 계약의 wire shape 과 poll 상태기계를 실제 HTTP 로 검증한다 ──
async function runSelftest() {
    const assert = require('assert');
    await new Promise((r) => server.listen(0, r));
    const base = `http://localhost:${server.address().port}`;
    const get = async (p) => { const r = await fetch(base + p); return { r, body: await r.text() }; };
    const postJson = async (p, m = 'POST') => (await fetch(base + p, { method: m })).json();

    // 1) 목록형 dispatch → 배열 + 숫자 id
    const list = await postJson('/infra/target-sources/ok/terraform-jobs/plan');
    assert(Array.isArray(list) && typeof list[0].id === 'number', 'list dispatch → [{id:number}]');

    // 2) 단건형 dispatch → 객체 + 숫자 id
    const single = await postJson('/infra/target-sources/ok/idc/terraform/action?jobType=APPLY&idcTerraformType=CX');
    assert(!Array.isArray(single) && typeof single.id === 'number', 'single dispatch → {id:number}');

    // 3) plan poll: RUNNING × RUNNING_POLLS → COMPLETED, DTO 필드명(terraformState/resultPath) 검증
    const planId = list[0].id;
    for (let i = 0; i < RUNNING_POLLS; i++) {
        const s = await (await fetch(`${base}/infra/terraform-jobs/plan/${planId}`)).json();
        assert.strictEqual(s.terraformState, 'RUNNING', `poll ${i} → RUNNING`);
    }
    const done = await (await fetch(`${base}/infra/terraform-jobs/plan/${planId}`)).json();
    assert.strictEqual(done.terraformState, 'COMPLETED', 'plan terminal → COMPLETED');
    assert(typeof done.resultPath === 'string' && done.resultPath.length > 0, 'success 는 resultPath 를 싣는다');

    // 4) destroy poll → DESTROYED terminal
    const destroy = await postJson('/infra/target-sources/ok/terraform-jobs/destroy', 'DELETE');
    const did = destroy[0].id;
    for (let i = 0; i < RUNNING_POLLS; i++) await fetch(`${base}/infra/terraform-jobs/destroy/${did}`);
    const dstate = await (await fetch(`${base}/infra/terraform-jobs/destroy/${did}`)).json();
    assert.strictEqual(dstate.terraformState, 'DESTROYED', 'destroy terminal → DESTROYED');

    // 5) fail-target → FAILED terminal + failReason
    const fail = await postJson('/infra/target-sources/fail-target/terraform-jobs/apply');
    const fid = fail[0].id;
    for (let i = 0; i < RUNNING_POLLS; i++) await fetch(`${base}/infra/terraform-jobs/apply/${fid}`);
    const fstate = await (await fetch(`${base}/infra/terraform-jobs/apply/${fid}`)).json();
    assert.strictEqual(fstate.terraformState, 'FAILED', 'fail-target terminal → FAILED');
    assert(fstate.failReason, 'FAILED 는 failReason 을 싣는다');

    // 6) result → text/plain 비어있지 않은 본문
    const { r: rr, body } = await get(`/infra/terraform-jobs/plan/${planId}/result`);
    assert(rr.headers.get('content-type').startsWith('text/plain'), 'result 는 text/plain');
    assert(body.length > 0, 'result 본문 비어있지 않음');

    // 7) network/ready: 정상 target → met:true, fail target → met:false
    assert.strictEqual((await (await fetch(`${base}/infra/network/ready?target=ok`)).json()).met, true, 'ready(ok) → true');
    assert.strictEqual((await (await fetch(`${base}/infra/network/ready?target=fail-net`)).json()).met, false, 'ready(fail) → false');

    // 8) cloud-provider: target 이름으로 provider 도출 (default AWS)
    const prov = async (t) => (await (await fetch(`${base}/infra/targets/${t}/cloud-provider`)).json()).provider;
    assert.strictEqual(await prov('svc-1'), 'AWS', 'default → AWS');
    assert.strictEqual(await prov('gcp-svc'), 'GCP', 'gcp target → GCP');
    assert.strictEqual(await prov('idc-cx'), 'IDC', 'idc target → IDC');

    // 9) shape 판별은 operation 경로만 본다 — targetSourceId 에 single-substring 이 들어가도 AWS service 는 목록
    const tricky = await postJson('/infra/target-sources/x-bdc-service-level-terraform-jobs/terraform-jobs/plan');
    assert(Array.isArray(tricky), 'targetSourceId substring 이 shape 을 오분류하지 않는다');

    // 10) 미상 GET 경로 → 404 (그럴싸한 status 200 으로 묻히지 않음)
    assert.strictEqual((await fetch(`${base}/infra/no-such-endpoint`)).status, 404, '미상 경로 → 404');
    assert.strictEqual((await fetch(`${base}/infra/terraform-jobs/plan/not-a-number`)).status, 404, '숫자 아닌 job id → 404');

    server.close();
    console.log('selftest OK');
}
