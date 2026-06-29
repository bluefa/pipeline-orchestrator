package com.bff.pipeline.model;

import java.util.List;

/** 파이프라인 타입에 대한 순서가 정해진 task 체인이다(ADR-016 §2: "(타입, 프로바이더)별 코드 기본값"). */
public record Recipe(List<RecipeStep> steps) {

    public Recipe {
        steps = List.copyOf(steps);
        if (steps.isEmpty()) { throw new IllegalArgumentException("a recipe needs at least one step"); }
    }
}
