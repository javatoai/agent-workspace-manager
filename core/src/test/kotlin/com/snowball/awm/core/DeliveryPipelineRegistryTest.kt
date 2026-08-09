package com.snowball.awm.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeliveryPipelineRegistryTest {
    @Test
    fun `registry exposes injected adapters without product-specific branching`() {
        val fake = object : DeliveryPipelineAdapter {
            override val descriptor = DeliveryPipelineDescriptor("preview", "预览环境", "预览历史")
            override fun execute(target: DeliveryTarget) = DeliveryExecution("preview", "1", "SUCCESS")
            override fun history(config: AppConfig, tasks: List<TaskManifest>) = emptyList<DeliveryHistoryRecord>()
        }
        val registry = DeliveryPipelineRegistry(listOf(fake))
        assertEquals(listOf(fake.descriptor), registry.descriptors())
        assertEquals(fake, registry.adapter("preview"))
        assertNull(registry.adapter("missing"))
    }
}
