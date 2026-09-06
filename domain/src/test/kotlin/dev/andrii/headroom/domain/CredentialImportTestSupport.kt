package dev.andrii.headroom.domain

import java.util.Base64
import java.util.zip.Deflater

object CredentialImportTestSupport {
    /** Encodes an arbitrary field map in the wire format, for negative tests. */
    fun encode(fields: Map<String, String>): String {
        val json = fields.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }
        val input = json.toByteArray()
        val deflater = Deflater(9)
        deflater.setInput(input)
        deflater.finish()
        val out = ByteArray(input.size * 2 + 64)
        val size = deflater.deflate(out)
        deflater.end()
        val packed = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(out.copyOf(size))
        return CredentialImport.SCHEME + packed
    }
}
