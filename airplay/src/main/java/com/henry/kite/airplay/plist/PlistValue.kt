package com.henry.kite.airplay.plist

/**
 * The subset of the plist type system that AirPlay RTSP bodies actually use.
 * No dates, no UIDs, no sets.
 */
sealed interface PlistValue {
    data class Dict(val entries: Map<String, PlistValue>) : PlistValue {
        operator fun get(key: String): PlistValue? = entries[key]
    }

    data class Array(val items: List<PlistValue>) : PlistValue

    data class Str(val value: String) : PlistValue

    data class Integer(val value: Long) : PlistValue

    data class Real(val value: Double) : PlistValue

    data class Bool(val value: Boolean) : PlistValue

    data class Data(val value: ByteArray) : PlistValue {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Data && value.contentEquals(other.value))

        override fun hashCode(): Int = value.contentHashCode()

        override fun toString(): String = "Data(${value.size} bytes)"
    }
}

// Ergonomic accessors, since reading a SETUP response is mostly digging for one key.

fun PlistValue?.asDict(): PlistValue.Dict? = this as? PlistValue.Dict
fun PlistValue?.asArray(): List<PlistValue>? = (this as? PlistValue.Array)?.items
fun PlistValue?.asString(): String? = (this as? PlistValue.Str)?.value
fun PlistValue?.asLong(): Long? = (this as? PlistValue.Integer)?.value
fun PlistValue?.asInt(): Int? = asLong()?.toInt()
fun PlistValue?.asDouble(): Double? = when (this) {
    is PlistValue.Real -> value
    is PlistValue.Integer -> value.toDouble()
    else -> null
}

fun PlistValue?.asBool(): Boolean? = when (this) {
    is PlistValue.Bool -> value
    is PlistValue.Integer -> value != 0L
    else -> null
}

fun PlistValue?.asData(): ByteArray? = (this as? PlistValue.Data)?.value

/** Small DSL so call sites read like the plist they build. */
class PlistDictBuilder {
    private val entries = linkedMapOf<String, PlistValue>()

    infix fun String.to(value: String) {
        entries[this] = PlistValue.Str(value)
    }
    infix fun String.to(value: Int) {
        entries[this] = PlistValue.Integer(value.toLong())
    }
    infix fun String.to(value: Long) {
        entries[this] = PlistValue.Integer(value)
    }
    infix fun String.to(value: Double) {
        entries[this] = PlistValue.Real(value)
    }
    infix fun String.to(value: Boolean) {
        entries[this] = PlistValue.Bool(value)
    }
    infix fun String.to(value: ByteArray) {
        entries[this] = PlistValue.Data(value)
    }
    infix fun String.to(value: PlistValue) {
        entries[this] = value
    }

    fun dict(key: String, block: PlistDictBuilder.() -> Unit) {
        entries[key] = PlistDictBuilder().apply(block).build()
    }

    fun array(key: String, vararg items: PlistValue) {
        entries[key] = PlistValue.Array(items.toList())
    }

    fun build(): PlistValue.Dict = PlistValue.Dict(entries.toMap())
}

fun plistDict(block: PlistDictBuilder.() -> Unit): PlistValue.Dict = PlistDictBuilder().apply(block).build()

fun plistDictOf(block: PlistDictBuilder.() -> Unit): PlistValue.Dict = plistDict(block)
