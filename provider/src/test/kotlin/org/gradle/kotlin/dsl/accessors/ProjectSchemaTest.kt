package org.gradle.kotlin.dsl.accessors

import org.gradle.api.reflect.TypeOf
import org.gradle.api.reflect.TypeOf.parameterizedTypeOf
import org.gradle.internal.classpath.ClassPath
import org.gradle.internal.classpath.DefaultClassPath
import org.gradle.kotlin.dsl.fixtures.TestWithTempFiles
import org.gradle.kotlin.dsl.fixtures.classEntriesFor
import org.gradle.kotlin.dsl.support.zipTo

import org.hamcrest.CoreMatchers.equalTo

import org.junit.Assert.assertThat
import org.junit.Assert.assertFalse
import org.junit.Test

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.*
import java.io.File
import java.lang.reflect.Array
import kotlin.reflect.KClass

class PublicGenericType<T> {}
class PublicComponentType {}
private class PrivateComponentType {}

class ProjectSchemaTest : TestWithTempFiles() {

    val loader = DynamicClassLoader()

    @Test
    fun `#accessibleProjectSchemaFrom rejects non-public or synthetic types`() {

        assertThat(
            accessibleProjectSchemaFrom(
                extensionSchema = mapOf(
                    "publicNonSynthetic" to publicNonSyntheticType,
                    "nonPublic" to nonPublicType,
                    "synthetic" to syntheticType),
                conventionPlugins = mapOf(
                    "publicNonSyntheticInstance" to instanceOf(publicNonSyntheticClass),
                    "nonPublicInstance" to instanceOf(nonPublicClass),
                    "syntheticInstance" to instanceOf(syntheticClass)),
                configurationNames = emptyList(),
                loader = loader),
            equalTo(
                ProjectSchema(
                    extensions = mapOf("publicNonSynthetic" to publicNonSyntheticType),
                    conventions = mapOf("publicNonSyntheticInstance" to publicNonSyntheticType),
                    configurations = emptyList())))
    }

    @Test
    fun `#isAccessible rejects array of non-public or synthetic type`() {

        assert(isAccessible(arrayTypeOf(publicNonSyntheticClass)))
        assertFalse(isAccessible(arrayTypeOf(nonPublicClass)))
        assertFalse(isAccessible(arrayTypeOf(syntheticClass)))
    }

    @Test
    fun `#isAccessible rejects parameterized type of non-public or synthetic type`() {

        assert(isAccessible(listTypeOf(publicNonSyntheticType)))
        assertFalse(isAccessible(listTypeOf(nonPublicType)))
        assertFalse(isAccessible(listTypeOf(syntheticType)))
    }

    @Test
    fun `#isLegalExtensionName rejects illegal Kotlin extension names`() {

        assert(isLegalExtensionName("foo_bar"))
        assert(isLegalExtensionName("foo-bar"))
        assert(isLegalExtensionName("foo bar"))

        assertFalse(isLegalExtensionName("foo`bar"))
        assertFalse(isLegalExtensionName("foo.bar"))
        assertFalse(isLegalExtensionName("foo/bar"))
        assertFalse(isLegalExtensionName("foo\\bar"))
    }

    @Test
    fun `non existing type is represented as inaccessible because it is non available`() {

        val typeString = "not.available.Type"

        val projectSchema = availableProjectSchemaFor(
            schemaWithExtensions("buildScan" to typeString),
            ClassPath.EMPTY)

        assertThat<TypeAccessibility>(
            projectSchema.extensions["buildScan"]!!,
            equalTo(inaccessible(typeString, InaccessibilityReason.NonAvailable(typeString))))
    }

    @Test
    fun `some existing public type is represented as accessible`() {

        val typeString = "some.available.Type"

        val projectSchema = availableProjectSchemaFor(
            schemaWithExtensions("buildScan" to typeString),
            classPathWithPublicType(typeString))

        assertThat<TypeAccessibility>(
            projectSchema.extensions["buildScan"]!!,
            equalTo(accessible(typeString)))
    }

    @Test
    fun `some existing private type is represented as inaccessible because it is non public`() {

        val typeString = "some.non.visible.Type"

        val projectSchema = availableProjectSchemaFor(
            schemaWithExtensions("buildScan" to typeString),
            classPathWithPrivateType(typeString))

        assertThat<TypeAccessibility>(
            projectSchema.extensions["buildScan"]!!,
            equalTo(inaccessible(typeString, InaccessibilityReason.NonPublic(typeString))))
    }

    @Test
    fun `some parameterized public type with public component type is represented as accessible`() {

        val genericTypeString = kotlinTypeStringFor(typeOf<PublicGenericType<PublicComponentType>>())

        val projectSchema = availableProjectSchemaFor(
            schemaWithExtensions("generic" to genericTypeString),
            classPathWith(PublicGenericType::class, PublicComponentType::class))

        assertThat<TypeAccessibility>(
            projectSchema.extensions["generic"]!!,
            equalTo(accessible(genericTypeString)))
    }

    @Test
    fun `some parameterized public type with non public component type is represented as inaccessible because its component type is not public`() {

        val genericTypeString = kotlinTypeStringFor(typeOf<PublicGenericType<PrivateComponentType>>())

        val projectSchema = availableProjectSchemaFor(
            schemaWithExtensions("generic" to genericTypeString),
            classPathWith(PublicGenericType::class, PrivateComponentType::class))

        assertThat<TypeAccessibility>(
            projectSchema.extensions["generic"]!!,
            equalTo(inaccessible(genericTypeString, InaccessibilityReason.NonPublic(PrivateComponentType::class.qualifiedName!!))))
    }

    @Test
    fun `some parameterized public type with non existing component type is represented as inaccessible because its component type is not available`() {

        val genericTypeString = kotlinTypeStringFor(typeOf<PublicGenericType<PublicComponentType>>())

        val projectSchema = availableProjectSchemaFor(
            schemaWithExtensions("generic" to genericTypeString),
            classPathWith(PublicGenericType::class))

        assertThat<TypeAccessibility>(
            projectSchema.extensions["generic"]!!,
            equalTo(inaccessible(genericTypeString, InaccessibilityReason.NonAvailable(PublicComponentType::class.qualifiedName!!))))
    }

    @Test
    fun `some public type existing inside a JAR is represented as accessible`() {

        val genericTypeString = kotlinTypeStringFor(typeOf<PublicGenericType<PublicComponentType>>())

        val projectSchema = availableProjectSchemaFor(
            schemaWithExtensions("generic" to genericTypeString),
            jarClassPathWith(PublicGenericType::class, PublicComponentType::class))

        assertThat<TypeAccessibility>(
            projectSchema.extensions["generic"]!!,
            equalTo(accessible(genericTypeString)))
    }

    private
    fun jarClassPathWith(vararg classes: KClass<*>): ClassPath =
        DefaultClassPath.of(listOf(file("cp.jar").also { jar ->
            zipTo(jar, classEntriesFor(*classes.map { it.java }.toTypedArray()))
        }))

    private
    fun classPathWith(vararg classes: KClass<*>): ClassPath =
        DefaultClassPath.of(listOf(file("cp").also { rootDir ->
            for ((path, bytes) in classEntriesFor(*classes.map { it.java }.toTypedArray())) {
                File(rootDir, path).apply {
                    parentFile.mkdirs()
                    writeBytes(bytes)
                }
            }
        }))

    private
    fun classPathWithPublicType(name: String) =
        classPathWithType(name, ACC_PUBLIC)

    private
    fun classPathWithPrivateType(name: String) =
        classPathWithType(name, ACC_PRIVATE)

    private
    fun classPathWithType(name: String, vararg modifiers: Int): ClassPath =
        DefaultClassPath.of(listOf(file("cp").also { rootDir ->
            classFileForType(name, rootDir, *modifiers)
        }))

    private
    fun classFileForType(name: String, rootDir: File, vararg modifiers: Int) {
        File(rootDir, "${name.replace(".", "/")}.class").apply {
            parentFile.mkdirs()
            writeBytes(classBytesOf(name, *modifiers))
        }
    }

    private
    fun schemaWithExtensions(vararg pairs: Pair<String, String>) =
        ProjectSchema<String>(
            extensions = mapOf(*pairs),
            conventions = emptyMap(),
            configurations = emptyList()
        )


    fun instanceOf(`class`: Class<*>): Any =
        `class`.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

    fun arrayTypeOf(componentType: Class<*>): TypeOf<*> =
        TypeOf.typeOf(Array.newInstance(componentType, 0)::class.java)

    fun listTypeOf(componentType: TypeOf<*>): TypeOf<*> =
        parameterizedTypeOf(object : TypeOf<List<*>>() {}, componentType)

    val publicNonSyntheticType by lazy {
        TypeOf.typeOf(publicNonSyntheticClass)!!
    }

    val publicNonSyntheticClass by lazy {
        defineClass("PublicNonSynthetic", ACC_PUBLIC)
    }

    val nonPublicType by lazy {
        TypeOf.typeOf(nonPublicClass)!!
    }

    val nonPublicClass by lazy {
        defineClass("NonPublic")
    }

    val syntheticType by lazy {
        TypeOf.typeOf(syntheticClass)!!
    }

    val syntheticClass by lazy {
        defineClass("Synthetic", ACC_PUBLIC, ACC_SYNTHETIC)
    }

    fun defineClass(name: String, vararg modifiers: Int): Class<*> =
        loader.defineClass(name, classBytesOf(name, *modifiers))

    fun classBytesOf(name: String, vararg modifiers: Int): ByteArray =
        ClassWriter(0).run {
            visit(V1_7, modifiers.fold(0, Int::plus), name, null, "java/lang/Object", null)
            visitMethod(ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(ALOAD, 0)
                visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitInsn(RETURN)
                visitMaxs(1, 1)
            }
            visitEnd()
            toByteArray()
        }

    class DynamicClassLoader : ClassLoader() {
        fun defineClass(name: String, bytes: ByteArray): Class<*> =
            defineClass(name, bytes, 0, bytes.size)!!
    }
}
