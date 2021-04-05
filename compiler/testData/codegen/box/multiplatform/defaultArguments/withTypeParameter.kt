// !LANGUAGE: +MultiPlatformProjects
// WITH_RUNTIME
// MODULE: lib
// FILE: common.kt

expect fun <T> topLevel(a: T, b: (T) -> Int = { 1 }): String

expect class Foo() {
    fun <T> member(a: T, b: (T) -> Int = { 2 }): String
}

//expect class Bar<T>() {
//    fun member(a: T, b: (T) -> Int = { 3 }): String
//}

actual fun <T> topLevel(a: T, b: (T) -> Int): String = b(a).toString()

actual class Foo actual constructor() {
    actual fun <T> member(a: T, b: (T) -> Int): String = b(a).toString()
}

//actual class Bar<T> actual constructor() {
//    actual fun member(a: T, b: (T) -> Int): String = b(a).toString()
//}

// MODULE: main(lib)
// FILE: main.kt

import kotlin.test.assertEquals

fun box(): String {
    assertEquals("1", topLevel("OK"))
    assertEquals("73", topLevel("OK") { 73 })

    val foo = Foo()
    assertEquals("2", foo.member("OK"))
    assertEquals("42", foo.member("OK") { 42 })

//    val bar = Bar<String>()
//    assertEquals("3", bar.member("OK"))
//    assertEquals("37", bar.member("OK") { 37 })

    return "OK"
}
