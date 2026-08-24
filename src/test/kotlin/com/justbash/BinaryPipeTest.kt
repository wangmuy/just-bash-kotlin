package com.justbash

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

class BinaryPipeTest {
    @Test @Timeout(10, unit = TimeUnit.SECONDS)
    fun `binary pipe gzip gunzip`() = runBlocking {
        val r = BashEnvironment().exec("echo 'hello binary pipe' | gzip -c | gunzip")
        assertEquals("hello binary pipe\n", r.stdout)
    }
    @Test @Timeout(10, unit = TimeUnit.SECONDS)
    fun `binary pipe base64`() = runBlocking {
        val r = BashEnvironment().exec("echo 'test-data' | base64")
        assertEquals("dGVzdC1kYXRhCg==\n", r.stdout)
    }
}
