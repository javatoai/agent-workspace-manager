package com.snowball.awm.core

import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class GenbuProductionVersionProviderTest {
    @Test
    fun `parser returns the single ready production version`() {
        val snapshot = GenbuProductionSnapshotParser.parse(
            """
            {
              "service": "fp-android-transit-service",
              "environment": "PRD",
              "pods": [
                {"pod_name":"pod-a","app_version":"3.11.70","restart_count":0,"phase":"Running","ready":true},
                {"pod_name":"pod-b","app_version":"3.11.70","restart_count":0,"phase":"Running","ready":true}
              ]
            }
            """.trimIndent(),
        )

        assertEquals("3.11.70", snapshot.version)
        assertEquals(2, snapshot.pods.size)
    }

    @Test
    fun `parser rejects mixed versions and unhealthy pods`() {
        assertFailsWith<IllegalStateException> {
            GenbuProductionSnapshotParser.parse(
                """
                {"service":"svc","environment":"PRD","pods":[
                  {"pod_name":"a","app_version":"1.0.0","restart_count":0,"phase":"Running","ready":true},
                  {"pod_name":"b","app_version":"1.0.1","restart_count":0,"phase":"Running","ready":true}
                ]}
                """.trimIndent(),
            )
        }
        assertFailsWith<IllegalStateException> {
            GenbuProductionSnapshotParser.parse(
                """{"service":"svc","environment":"PRD","pods":[
                  {"pod_name":"a","app_version":"","restart_count":0,"phase":"Running","ready":true},
                  {"pod_name":"b","app_version":"1.0.0","restart_count":0,"phase":"Running","ready":true}
                ]}""",
            )
        }
        assertFailsWith<IllegalStateException> {
            GenbuProductionSnapshotParser.parse(
                """{"service":"svc","environment":"PRD","pods":[
                  {"pod_name":"a","app_version":"1.0.0","phase":"Running","ready":true}
                ]}""",
            )
        }
        assertFailsWith<IllegalStateException> {
            GenbuProductionSnapshotParser.parse(
                """
                {"service":"svc","environment":"PRD","pods":[
                  {"pod_name":"a","app_version":"1.0.0","restart_count":0,"phase":"Running","ready":false}
                ]}
                """.trimIndent(),
            )
        }
        assertFailsWith<IllegalStateException> {
            GenbuProductionSnapshotParser.parse(
                """{"service":"svc","environment":"PRD","pods":[
                  {"pod_name":"a","app_version":"1.0.0","restart_count":2,"phase":"Running","ready":true}
                ]}""",
            )
        }
        assertFailsWith<IllegalStateException> {
            GenbuProductionSnapshotParser.parse(
                """{"service":"svc","environment":"UAT","pods":[
                  {"pod_name":"a","app_version":"1.0.0","restart_count":0,"phase":"Running","ready":true}
                ]}""",
            )
        }
    }

    @Test
    fun `provider distinguishes command failure from an unhealthy successful snapshot`() {
        fun runner(result: CommandResult) = object : CommandRunner {
            override fun run(
                command: List<String>,
                workingDirectory: Path?,
                timeout: Duration,
                environment: Map<String, String>,
            ) = result
        }
        val executable = GenbuExecutable { "genbu" }
        assertFailsWith<ProductionVersionUnavailableException> {
            GenbuProductionVersionProvider(executable, runner(CommandResult(1, "", "offline"))).current("service")
        }
        val unhealthy = assertFailsWith<IllegalStateException> {
            GenbuProductionVersionProvider(executable, runner(CommandResult(
                0,
                """{"service":"svc","environment":"PRD","pods":[
                  {"pod_name":"a","app_version":"1.0.0","restart_count":1,"phase":"Running","ready":true}
                ]}""",
                "",
            ))).current("service")
        }
        assertFalse(unhealthy is ProductionVersionUnavailableException)
    }
}
