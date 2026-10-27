package com.unshoo.pixelmusic.data.service.speakers

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SonosGroupXmlTest {
    private val topology = """<ZoneGroups>
        <ZoneGroup Coordinator="A"><ZoneGroupMember UUID="A"/><ZoneGroupMember UUID="B"><Satellite UUID="R"/></ZoneGroupMember><ZoneGroupMember UUID="S" Invisible="1"/></ZoneGroup>
        <ZoneGroup Coordinator="C"><ZoneGroupMember UUID="C"/></ZoneGroup>
        </ZoneGroups>"""
    @Test fun acceptsSelectedRoomsWithoutBreakingBondedSatellites() {
        val rooms = SonosGroupXml.topology(topology)
        assertTrue(SonosGroupXml.verifiedGroup(rooms, "A", setOf("A", "B")))
        assertFalse(rooms.first { it.id == "R" }.visible)
        assertFalse(rooms.first { it.id == "S" }.visible)
    }
    @Test fun rejectsMissingMembersWrongCoordinatorAndExtraRooms() {
        val rooms = SonosGroupXml.topology(topology)
        assertFalse(SonosGroupXml.verifiedGroup(rooms, "A", setOf("A", "C")))
        assertFalse(SonosGroupXml.verifiedGroup(rooms, "B", setOf("A", "B")))
        assertFalse(SonosGroupXml.verifiedGroup(rooms, "A", setOf("A")))
        assertFalse(SonosGroupXml.verifiedGroup(rooms, "A", emptySet()))
    }
    @Test fun excludesBondedSpeakerFromSelectableGroup() {
        assertFalse(SonosGroupXml.verifiedGroup(SonosGroupXml.topology(topology), "A", setOf("A", "B", "R")))
    }
    @Test fun decodesEscapedTopologyFromNamespacedSoap() {
        val soap = """<s:Envelope xmlns:s="urn:soap"><s:Body><u:GetZoneGroupStateResponse xmlns:u="urn:sonos"><ZoneGroupState>&lt;ZoneGroups&gt;&lt;ZoneGroup Coordinator="A"&gt;&lt;ZoneGroupMember UUID="A"/&gt;&lt;/ZoneGroup&gt;&lt;/ZoneGroups&gt;</ZoneGroupState></u:GetZoneGroupStateResponse></s:Body></s:Envelope>"""
        assertTrue(SonosGroupXml.verifiedGroup(SonosGroupXml.topology(SonosGroupXml.value(soap, "ZoneGroupState")), "A", setOf("A")))
    }
    @Test fun rejectsDtdAndExternalEntities() {
        assertThrows(IllegalArgumentException::class.java) {
            SonosGroupXml.document("""<!DOCTYPE x [<!ENTITY ext SYSTEM "file:///etc/passwd">]><x>&ext;</x>""")
        }
    }
}
