package com.soywiz.korge.view.ktree

import com.soywiz.korge.view.*
import com.soywiz.korim.bitmap.*
import com.soywiz.korim.color.*
import com.soywiz.korim.format.*
import com.soywiz.korio.file.*
import com.soywiz.korio.file.std.*
import com.soywiz.korio.serialization.xml.*
import kotlin.reflect.*

interface KTreeSerializerHolder {
    val serializer: KTreeSerializer
}

open class KTreeSerializer : KTreeSerializerHolder {
    override val serializer get() = this

    data class Registration(
        val deserializer: suspend (xml: Xml) -> View?,
        val serializer: suspend (view: View, properties: MutableMap<String, Any?>) -> Xml?
    )

    private val registrations = arrayListOf<Registration>()

    fun register(registration: Registration) {
        registrations.add(registration)
    }

    fun register(deserializer: suspend (xml: Xml) -> View, serializer: suspend (view: View, properties: MutableMap<String, Any?>) -> Xml?) {
        register(Registration(deserializer, serializer))
    }

    open suspend fun ktreeToViewTree(xml: Xml, currentVfs: VfsFile): View {
        var view: View? = null
        when (xml.nameLC) {
            "solidrect" -> view = SolidRect(100, 100, Colors.RED)
            "ellipse" -> view = Ellipse(50.0, 50.0, Colors.RED)
            "container" -> view = Container()
            "image" -> view = Image(Bitmaps.transparent).also { image ->
                image.forceLoadSourceImage(currentVfs, xml.str("sourceImage"))
            }
            else -> {
                for (registration in registrations) {
                    view = registration.deserializer(xml)
                    if (view != null) break
                }
            }
        }

        if (view == null) {
            TODO("Unsupported node ${xml.name}")
        }

        fun double(prop: KMutableProperty0<Double>, defaultValue: Double) {
            prop.set(xml.double(prop.name, defaultValue))
        }

        fun color(prop: KMutableProperty0<RGBA>, defaultValue: RGBA) {
            prop.set(Colors[(xml.strNull(prop.name) ?: defaultValue.hexString)])
        }

        fun string(prop: KMutableProperty0<String>, defaultValue: String) {
            prop.set(xml.str(prop.name, defaultValue))
        }
        fun stringNull(prop: KMutableProperty0<String?>) {
            prop.set(xml.strNull(prop.name))
        }

        stringNull(view::name)
        color(view::colorMul, Colors.WHITE)
        double(view::alpha, 1.0)
        double(view::speed, 1.0)
        double(view::ratio, 0.0)
        double(view::x, 0.0)
        double(view::y, 0.0)
        double(view::rotationDegrees, 0.0)
        double(view::scaleX, 1.0)
        double(view::scaleY, 1.0)
        double(view::skewX, 0.0)
        double(view::skewY, 0.0)
        if (view is RectBase) {
            double(view::anchorX, 0.0)
            double(view::anchorY, 0.0)
            double(view::width, 100.0)
            double(view::height, 100.0)
        }
        if (view is Container) {
            for (node in xml.allNodeChildren) {
                view.addChild(ktreeToViewTree(node, currentVfs))
            }
        }
        return view
    }
    
    open suspend fun viewTreeToKTree(view: View, currentVfs: VfsFile): Xml {
        val properties = LinkedHashMap<String, Any?>()

        fun add(prop: KProperty0<*>) {
            properties[prop.name] = prop.get()
        }

        if (view.name !== null) add(view::name)
        if (view.colorMul != Colors.WHITE) {
            properties["colorMul"] = view.colorMul.hexString
        }
        if (view.alpha != 1.0) add(view::alpha)
        if (view.speed != 1.0) add(view::speed)
        if (view.ratio != 0.0) add(view::ratio)
        if (view.x != 0.0) add(view::x)
        if (view.y != 0.0) add(view::y)
        if (view.rotationDegrees != 0.0) add(view::rotationDegrees)
        if (view.scaleX != 1.0) add(view::scaleX)
        if (view.scaleY != 1.0) add(view::scaleY)
        if (view.skewX != 0.0) add(view::skewX)
        if (view.skewY != 0.0) add(view::skewY)
        if (view is RectBase) {
            if (view.anchorX != 0.0) add(view::anchorX)
            if (view.anchorY != 0.0) add(view::anchorY)
            add(view::width)
            add(view::height)
        }
        if (view is Image) {
            add(view::sourceImage)
        }

        return registrations.map { it.serializer(view, properties) }.firstOrNull() ?: when (view) {
            is SolidRect -> Xml("solidrect", properties)
            is Ellipse -> Xml("ellipse", properties)
            is Image -> Xml("image", properties)
            is Container -> Xml("container", properties) {
                view.forEachChildren { this@Xml.node(viewTreeToKTree(it, currentVfs)) }
            }
            else -> error("Don't know how to serialize $view")
        }
    }
}

suspend fun Xml.ktreeToViewTree(views: Views): View = views.serializer.ktreeToViewTree(this, views.currentVfs)
suspend fun View.viewTreeToKTree(views: Views): Xml = views.serializer.viewTreeToKTree(this, views.currentVfs)

suspend fun Xml.ktreeToViewTree(serializer: KTreeSerializerHolder, currentVfs: VfsFile): View = serializer.serializer.ktreeToViewTree(this, currentVfs)
suspend fun View.viewTreeToKTree(serializer: KTreeSerializerHolder, currentVfs: VfsFile): Xml = serializer.serializer.viewTreeToKTree(this, currentVfs)
suspend fun VfsFile.readKTree(serializer: KTreeSerializerHolder): View = readXml().ktreeToViewTree(serializer, this.parent)
