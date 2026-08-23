package com.snowball.awm.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
                """
                {"service":"svc","environment":"PRD","pods":[
                  {"pod_name":"a","app_version":"1.0.0","restart_count":0,"phase":"Running","ready":false}
                ]}
                """.trimIndent(),
            )
        }
    }
}
