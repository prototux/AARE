package net.prototux.aare.host

import org.junit.Assert.assertEquals
import org.junit.Test

class StandalonePackagesTest {
    @Test
    fun theAssistantIsHostedEvenWithoutTheAllowList() {
        assertEquals(listOf("com.anthropic.claude"), standalonePackages(false, listOf("org.newpipe"), "com.anthropic.claude", emptySet()))
    }

    @Test
    fun anAssistantWithACarAppIsNotHostedAgain() {
        assertEquals(listOf("org.newpipe"), standalonePackages(true, listOf("org.newpipe"), "net.prototux.lapin", setOf("net.prototux.lapin")))
    }

    @Test
    fun anAllowedAssistantShowsOnce() {
        assertEquals(listOf("com.openai.chatgpt"), standalonePackages(true, listOf("com.openai.chatgpt"), "com.openai.chatgpt", emptySet()))
    }

    @Test
    fun noAssistantLeavesTheAllowList() {
        assertEquals(listOf("org.newpipe"), standalonePackages(true, listOf("org.newpipe"), null, emptySet()))
        assertEquals(emptyList<String>(), standalonePackages(false, listOf("org.newpipe"), null, emptySet()))
    }

    @Test
    fun navigationAppsWithoutACarAppAreAddedOnce() {
        assertEquals(listOf("com.google.android.apps.maps"),
            standalonePackages(false, emptyList(), null, setOf("com.waze"), listOf("com.waze", "com.google.android.apps.maps")))
        assertEquals(listOf("com.google.android.apps.maps"),
            standalonePackages(true, listOf("com.google.android.apps.maps"), null, emptySet(), listOf("com.google.android.apps.maps")))
    }
}
