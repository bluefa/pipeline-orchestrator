package com.bff.pipeline.im;

/** A Terraform job poll result (transport value — not persisted). */
public record TerraformPoll(boolean finished, boolean succeeded) {

    public static TerraformPoll running() {
        return new TerraformPoll(false, false);
    }

    public static TerraformPoll success() {
        return new TerraformPoll(true, true);
    }

    public static TerraformPoll failure() {
        return new TerraformPoll(true, false);
    }
}
