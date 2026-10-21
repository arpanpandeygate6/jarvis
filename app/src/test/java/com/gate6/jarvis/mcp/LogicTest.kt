package com.gate6.jarvis.mcp

import java.io.BufferedReader
import java.io.StringReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseReaderTest {
    @Test
    fun joinsMultiLineDataAndSkipsComments() {
        val stream = ": keep-alive\n\nevent: message\nid: 1\ndata: {\"a\":\ndata: 1}\n\ndata: second\n"
        val r = SseReader(BufferedReader(StringReader(stream)))
        assertEquals("{\"a\":\n1}", r.next())
        assertEquals("second", r.next())
        assertNull(r.next())
    }
}

class McpResultsTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun joinsTextAndMarksErrors() {
        assertEquals("a\nb", McpResults.toText(obj("""{"content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}""")))
        assertEquals("Error: boom", McpResults.toText(obj("""{"isError":true,"content":[{"type":"text","text":"boom"}]}""")))
        assertEquals("Done.", McpResults.toText(obj("""{"content":[]}""")))
        assertEquals("""{"n":1}""", McpResults.toText(obj("""{"content":[],"structuredContent":{"n":1}}""")))
    }

    @Test
    fun truncatesLongResults() {
        val long = "x".repeat(McpResults.MAX_CHARS + 100)
        val text = McpResults.toText(obj("""{"content":[{"type":"text","text":"$long"}]}"""))
        assertTrue(text.endsWith("(truncated)"))
        assertTrue(text.length < McpResults.MAX_CHARS + 20)
    }
}

class ToolArgsTest {
    private val schema = Json.parseToJsonElement(
        """
        {"type":"object","properties":{
          "count":{"type":"integer"},
          "filters":{"type":"object"},
          "labels":{"type":"array","items":{"type":"string"}},
          "title":{"type":"string"},
          "maybe":{"anyOf":[{"type":"null"},{"type":"integer"}]},
          "nested":{"type":"object","properties":{"page":{"type":"integer"}}}
        }}
        """
    ).jsonObject

    @Test
    fun restoresJsonStringsAndIntegers() {
        val args = Json.parseToJsonElement(
            """{"count":5.0,"filters":"{\"state\":\"open\"}","labels":["a"],"title":"{not json","maybe":3.0,"nested":{"page":2.0}}"""
        ).jsonObject
        val out = ToolArgs.decode(args, schema)
        assertEquals(JsonPrimitive(5L), out["count"])
        assertEquals(JsonPrimitive("open"), (out["filters"] as JsonObject)["state"])
        assertEquals(JsonArray(listOf(JsonPrimitive("a"))), out["labels"])
        assertEquals(JsonPrimitive("{not json"), out["title"]) // strings stay strings
        assertEquals(JsonPrimitive(3L), out["maybe"])
        assertEquals(JsonPrimitive(2L), (out["nested"] as JsonObject)["page"])
    }

    @Test
    fun leavesNonWholeNumbersAndBadJsonAlone() {
        val out = ToolArgs.decode(
            Json.parseToJsonElement("""{"count":2.5,"filters":"oops"}""").jsonObject, schema
        )
        assertEquals(JsonPrimitive(2.5), out["count"])
        assertEquals(JsonPrimitive("oops"), out["filters"])
    }
}

class ConfirmationTest {
    private val asked = "I'll create an issue called login bug in the web repo. Should I go ahead?"

    @Test
    fun allowsOnlyAskedAndClearlyConfirmed() {
        assertTrue(Confirmation.check(true, asked, "yes please").allowed)
        assertTrue(Confirmation.check(true, asked, "Haan, kar do").allowed)
        assertTrue(Confirmation.check(true, asked, "Okay go ahead.").allowed)
    }

    @Test
    fun blocksWithoutFlagQuestionOrYes() {
        assertFalse(Confirmation.check(false, asked, "yes").allowed)
        assertFalse(Confirmation.check(true, "Sure, one sec.", "yes").allowed)
        assertFalse(Confirmation.check(true, "", "yes create it").allowed)
        assertFalse(Confirmation.check(true, asked, "no").allowed)
        assertFalse(Confirmation.check(true, asked, "yes, wait, not that repo").allowed)
        assertFalse(Confirmation.check(true, asked, "what was the title").allowed)
        assertFalse(Confirmation.check(true, asked, "").allowed)
    }

    @Test
    fun questionsAndLaughterAreNotConsent() {
        assertFalse(Confirmation.isClearYes("Okay, what branch is that again?"))
        assertFalse(Confirmation.isClearYes("okay which one"))
        assertFalse(Confirmation.isClearYes("ha ha"))
        assertFalse(Confirmation.isClearYes("sure, but how long will it take"))
        assertFalse(Confirmation.isClearYes("haan kaunsa wala"))
        assertTrue(Confirmation.isClearYes("yes go ahead and create it in the web repo"))
        assertFalse(Confirmation.isClearYes("yes " + "and then something else ".repeat(4)))
    }

    @Test
    fun wordBoundariesAvoidFalseYes() {
        assertFalse(Confirmation.isClearYes("what about the other one"))
        assertFalse(Confirmation.isClearYes("thanks"))
        assertFalse(Confirmation.isClearYes("token"))
    }
}

class ToolCatalogTest {
    private fun tool(name: String, readOnly: Boolean = false, schema: String = """{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]}""") =
        McpTool(name, null, "desc", Json.parseToJsonElement(schema).jsonObject, if (readOnly) true else false)

    @Test
    fun namesAreUniqueSanitizedAndRouted() {
        val c = ToolCatalog(
            listOf(
                ServerTools("1", "My GitHub!", listOf(tool("search issues"), tool("search-issues"), tool("x".repeat(80)))),
                ServerTools("2", "9 Docs", listOf(tool("read", readOnly = true))),
            )
        )
        val names = c.specs().map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { it.length <= 64 && Regex("[A-Za-z_][A-Za-z0-9_.-]*").matches(it) })
        assertEquals("my_github__search_issues", names[0])
        assertEquals("search-issues", c.route(names[1])!!.tool.name)
        assertEquals("_9_docs__read", names[3]) // names must not start with a digit
        assertEquals("2", c.route(names[3])!!.serverId)
    }

    @Test
    fun writeToolsGetRequiredConfirmParamReadToolsDoNot() {
        val c = ToolCatalog(listOf(ServerTools("1", "s", listOf(tool("write"), tool("read", readOnly = true)))))
        val (write, read) = c.specs()
        val wProps = write.parameters["properties"] as JsonObject
        assertTrue(Confirmation.PARAM in wProps)
        assertTrue((write.parameters["required"] as JsonArray).contains(JsonPrimitive(Confirmation.PARAM)))
        assertTrue((write.parameters["required"] as JsonArray).contains(JsonPrimitive("q")))
        assertFalse(Confirmation.PARAM in (read.parameters["properties"] as JsonObject))
    }

    @Test
    fun confirmParamWorksWithEmptySchemaAndLimitApplies() {
        val c = ToolCatalog(listOf(ServerTools("1", "s", (1..5).map { tool("t$it", schema = "{}") })), maxTools = 3)
        assertEquals(3, c.size)
        assertEquals(2, c.skipped)
        val p = c.specs().first().parameters
        assertEquals(JsonPrimitive("object"), p["type"])
        assertTrue(Confirmation.PARAM in (p["properties"] as JsonObject))
    }

    @Test
    fun trustOnlyAffectsUnannotatedTools() {
        val unmarked = McpTool("u", null, "", JsonObject(emptyMap()), null)
        val writes = McpTool("w", null, "", JsonObject(emptyMap()), false)
        val c = ToolCatalog(listOf(ServerTools("1", "s", listOf(unmarked, writes), trustUnmarked = true)))
        assertFalse(c.route("s__u")!!.needsConfirmation)
        assertTrue(c.route("s__w")!!.needsConfirmation)
        val strict = ToolCatalog(listOf(ServerTools("1", "s", listOf(unmarked))))
        assertTrue(strict.route("s__u")!!.needsConfirmation)
    }

    @Test
    fun parsesReadOnlyHint() {
        val t = McpClient.parseTool(Json.parseToJsonElement("""{"name":"a","annotations":{"readOnlyHint":true,"title":"A"}}"""))!!
        assertTrue(t.readOnly)
        assertEquals("A", t.title)
        assertNull(McpClient.parseTool(Json.parseToJsonElement("""{"name":"b"}"""))!!.readOnlyHint)
    }
}
