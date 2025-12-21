package com.bothbubbles.core.network.api

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter

/**
 * Moshi adapter for handling Unit type in API responses.
 *
 * This is needed because Moshi's default KotlinJsonAdapterFactory can't handle
 * Unit (Kotlin's void equivalent) as a type parameter in generic responses like
 * ApiResponse<Unit>. Without this adapter, deserializing such responses throws
 * IllegalArgumentException.
 *
 * Used for endpoints that return no meaningful data, e.g.:
 * - POST /api/v1/chat/{guid}/read
 * - POST /api/v1/chat/{guid}/unread
 * - DELETE /api/v1/chat/{guid}
 */
class UnitJsonAdapter : JsonAdapter<Unit>() {

    override fun fromJson(reader: JsonReader): Unit {
        // Skip any JSON value (null, object, array, primitive)
        // The actual value doesn't matter since Unit has no state
        reader.skipValue()
        return Unit
    }

    override fun toJson(writer: JsonWriter, value: Unit?) {
        // Write null for Unit since there's no meaningful value
        writer.nullValue()
    }
}
