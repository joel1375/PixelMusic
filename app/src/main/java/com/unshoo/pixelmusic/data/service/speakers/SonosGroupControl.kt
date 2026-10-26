package com.unshoo.pixelmusic.data.service.speakers

import com.airsonic.sender.dlna.pinLanUrl
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import javax.xml.parsers.DocumentBuilderFactory

internal data class SonosRoom(val id: String, val coordinator: String, val visible: Boolean)

/** Only visible rooms are selectable; stereo partners/surrounds remain bonded. */
internal object SonosGroupXml {
    fun document(xml: String): org.w3c.dom.Document {
        require(xml.length <= 1024 * 1024) { "Sonos response too large" }
        require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "Unsupported XML declaration" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }
    fun value(xml: String, tag: String): String {
        val nodes = document(xml).getElementsByTagNameNS("*", tag)
        check(nodes.length > 0) { "Missing Sonos $tag" }
        return nodes.item(0).textContent.trim()
    }
    fun topology(xml: String): List<SonosRoom> {
        val groups = document(xml).getElementsByTagNameNS("*", "ZoneGroup")
        val result = mutableListOf<SonosRoom>()
        for (i in 0 until groups.length) {
            val group = groups.item(i) as Element
            val coordinator = group.getAttribute("Coordinator")
            val members = group.getElementsByTagNameNS("*", "ZoneGroupMember")
            for (j in 0 until members.length) {
                val member = members.item(j) as Element
                result.add(SonosRoom(member.getAttribute("UUID"), coordinator, member.getAttribute("Invisible") != "1"))
                val satellites = member.getElementsByTagNameNS("*", "Satellite")
                for (k in 0 until satellites.length) {
                    result.add(SonosRoom((satellites.item(k) as Element).getAttribute("UUID"), coordinator, false))
                }
            }
        }
        return result
    }
    fun verifiedGroup(rooms: List<SonosRoom>, leader: String, selected: Set<String>): Boolean {
        if (selected.isEmpty() || leader !in selected) return false
        val actual = rooms.filter { it.visible && it.coordinator == leader }.map { it.id }.toSet()
        return actual == selected && selected.all { id -> rooms.any { it.id == id && it.visible && it.coordinator == leader } }
    }
}

/** Local Sonos SOAP control; all endpoints are pinned to private LAN addresses. */
internal class SonosGroupControl(val host: String) {
    private fun request(path: String, service: String? = null, action: String? = null, params: String = ""): String {
        val pinned = pinLanUrl("http://$host:1400$path") ?: error("Sonos must be on your local Wi-Fi")
        val connection = (pinned.url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 2500; readTimeout = 4000; instanceFollowRedirects = false
            setRequestProperty("Host", pinned.hostHeader)
            setRequestProperty("Connection", "close")
            if (action != null) {
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Content-Type", "text/xml; charset=utf-8")
                setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:$service:1#$action\"")
            }
        }
        try {
            if (action != null) {
                val body = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="urn:schemas-upnp-org:service:$service:1">$params</u:$action></s:Body></s:Envelope>"""
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            check(connection.responseCode in 200..299) { "Sonos $action failed (${connection.responseCode})" }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 1024 * 1024) { "Sonos response too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            check(bytes.size <= 1024 * 1024) { "Sonos response too large" }
            val text = String(bytes, Charsets.UTF_8)
            check(!text.contains(":Fault") && !text.contains("<Fault")) { "Sonos rejected $action" }
            return text
        } finally { connection.disconnect() }
    }
    fun identity(): String {
        val xml = request("/xml/device_description.xml")
        check(SonosGroupXml.value(xml, "manufacturer").contains("Sonos", true)) { "Receiver is not a Sonos speaker" }
        return SonosGroupXml.value(xml, "UDN").removePrefix("uuid:").also {
            require(it.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid Sonos room ID" }
        }
    }
    fun topology() = SonosGroupXml.topology(SonosGroupXml.value(request("/ZoneGroupTopology/Control", "ZoneGroupTopology", "GetZoneGroupState"), "ZoneGroupState"))
    fun standalone() { request("/MediaRenderer/AVTransport/Control", "AVTransport", "BecomeCoordinatorOfStandaloneGroup", "<InstanceID>0</InstanceID>") }
    fun join(leaderId: String) {
        require(leaderId.matches(Regex("[A-Za-z0-9_-]+")))
        request("/MediaRenderer/AVTransport/Control", "AVTransport", "SetAVTransportURI", "<InstanceID>0</InstanceID><CurrentURI>x-rincon:$leaderId</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")
    }
    fun currentUri(): String = SonosGroupXml.value(request("/MediaRenderer/AVTransport/Control", "AVTransport", "GetMediaInfo", "<InstanceID>0</InstanceID>"), "CurrentURI")
    fun volume(): Float = SonosGroupXml.value(request("/MediaRenderer/RenderingControl/Control", "RenderingControl", "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>"), "CurrentVolume").toInt().coerceIn(0, 100) / 100f
    fun setVolume(value: Float) {
        request("/MediaRenderer/RenderingControl/Control", "RenderingControl", "SetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>${(value.coerceIn(0f, 1f) * 100).toInt()}</DesiredVolume>")
    }
}
