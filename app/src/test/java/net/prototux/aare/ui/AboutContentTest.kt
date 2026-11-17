package net.prototux.aare.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutContentTest {
    /** A typo or stray space here would silently 404 the handle's GitHub profile link. */
    @Test
    fun everyHandleLooksLikeAGitHubHandle() {
        val handle = Regex("[A-Za-z0-9](-?[A-Za-z0-9]){0,38}")
        AboutThanks.GROUPS.forEach { group ->
            group.handles.forEach { assertTrue("not a GitHub handle: \"$it\"", handle.matches(it)) }
        }
    }

    /** Every group must have a title and at least one name, or the heading would show an empty list. */
    @Test
    fun everyGroupIsNonEmpty() {
        AboutThanks.GROUPS.forEach { group ->
            assertTrue("group ${group.titleRes} has no handles", group.handles.isNotEmpty())
        }
    }

    /** #124-#188: everyone whose merged pull request isn't prototux's own is already thanked by name. */
    @Test
    fun everyMergedPrAuthorIsThanked() {
        val thanked = AboutThanks.GROUPS.flatMap { it.handles }.toSet()
        val prAuthors = setOf(
            "akanshu-sapra", "dustfeather", "filipeniquini", "freezed23", "jameswynn",
            "jooray", "JVKeller", "KrishnaX12", "l0rdg3x", "MattMaaaan", "pafolol", "s-ratashnyuk",
        )
        assertEquals(emptySet<String>(), prAuthors - thanked)
    }

    @Test
    fun profileUrlIsBuiltFromTheHandle() {
        assertEquals("https://github.com/dustfeather", AboutLinks.profile("dustfeather"))
    }

    /** A blank name, licence or a non-https URL would show broken credit or dead-end a tap. */
    @Test
    fun everyOssComponentHasANameALicenceAndAnHttpsUrl() {
        AboutOss.GROUPS.forEach { group ->
            assertTrue("group ${group.titleRes} has no components", group.components.isNotEmpty())
            group.components.forEach { component ->
                assertTrue("blank name for component with licence \"${component.licence}\"", component.name.isNotBlank())
                assertTrue("blank licence for \"${component.name}\"", component.licence.isNotBlank())
                assertTrue("not an https URL for \"${component.name}\": \"${component.url}\"", component.url.startsWith("https://"))
            }
        }
    }
}
