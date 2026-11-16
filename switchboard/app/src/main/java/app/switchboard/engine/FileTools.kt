package app.switchboard.engine

import app.switchboard.net.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Tools that create files on the phone and put them in the chat. */
object FileTools {
    const val GENERATE_IMAGE = "generate_image"
    const val CREATE_DOCUMENT = "create_document"
    const val TEXT_TO_SPEECH = "text_to_speech"
    const val GENERATE_VIDEO = "generate_video"
    val names = setOf(GENERATE_IMAGE, CREATE_DOCUMENT, TEXT_TO_SPEECH, GENERATE_VIDEO)

    private fun obj(required: List<String>, props: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("type", "object"); putJsonObject("properties", props); put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }
    private fun kotlinx.serialization.json.JsonObjectBuilder.str(name: String, desc: String) = putJsonObject(name) { put("type", "string"); put("description", desc) }

    val generateImage = ToolSpec(GENERATE_IMAGE,
        "Create an image (illustration, photo-style picture, logo, icon, diagram art) with the user's image model and show it in the chat. " +
            "Use this whenever the user asks for a picture, instead of saying you can't make images.",
        obj(listOf("prompt")) {
            str("prompt", "Detailed description: subject, style, composition, colors, lighting, mood")
            str("filename", "Optional file name without extension")
        })

    val createDocument = ToolSpec(CREATE_DOCUMENT,
        "Create a file and attach it to the chat. Formats: pdf and docx (from Markdown: # headings, - lists, **bold**, *italic*), " +
            "xlsx (from CSV in 'content', or several sheets in 'sheets'), zip (of workspace files listed in 'files'), " +
            "or any text format by extension (txt, md, html, csv, json, svg, xml, ics, vcf, py, js, kt, ...). For binary formats not listed, use write_file with base64.",
        obj(listOf("format", "filename")) {
            str("format", "pdf, docx, xlsx, zip, or a text extension such as md, html, csv, json, svg")
            str("filename", "File name, e.g. report.pdf")
            str("title", "Optional title shown at the top (pdf, docx)")
            str("content", "Markdown for pdf/docx, CSV for xlsx, or the full text for text formats")
            putJsonObject("sheets") {
                put("type", "array"); put("description", "xlsx with several sheets: [{\"name\":\"Q1\",\"csv\":\"a,b\\n1,2\"}]")
                putJsonObject("items") { put("type", "object"); putJsonObject("properties") { str("name", "Sheet name"); str("csv", "Sheet data as CSV") } }
            }
            putJsonObject("files") { put("type", "array"); put("description", "zip: workspace files or folders to include"); putJsonObject("items") { put("type", "string") } }
        })

    val generateVideo = ToolSpec(GENERATE_VIDEO, "Create a short video clip with the user's video models and attach it to the chat (can take several minutes).",
        obj(listOf("prompt")) {
            str("prompt", "Detailed description of the scene, action, camera and style")
            str("filename", "Optional file name without extension")
        })

    val textToSpeech = ToolSpec(TEXT_TO_SPEECH, "Turn text into spoken audio with the user's audio models (or the phone's voice engine) and attach it to the chat.",
        obj(listOf("text")) {
            str("text", "What to say (up to a few thousand characters)")
            str("language", "Optional language tag for the phone's voice, e.g. en-US, fil-PH, es-ES")
            str("voice", "Optional voice name for audio models, e.g. alloy, verse, nova")
            str("filename", "Optional file name without extension")
        })
}
