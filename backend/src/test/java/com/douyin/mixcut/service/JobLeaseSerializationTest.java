package com.douyin.mixcut.service;

import com.douyin.mixcut.domain.Job;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class JobLeaseSerializationTest {
    @Test
    void leaseTokenIsNeverSerializedToApiPayload() throws Exception {
        Job job = new Job();
        job.setLeaseToken("sensitive-worker-token");
        String json = new ObjectMapper().writeValueAsString(job);
        assertFalse(json.contains("sensitive-worker-token"));
        assertFalse(json.contains("leaseToken"));
    }
}
