package dev.codex.sentinel.punishment;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PunishmentPolicyTest {
    @Test void monitoringOnlyBlocksAllAutomaticActions() { var p=new PunishmentPolicy(true,true,2,false,true,3,false); assertEquals(PunishmentPolicy.Action.NONE,p.decide(99,true)); }
    @Test void disabledKickNeverExecutes() { var p=new PunishmentPolicy(false,false,2,false,false,90,false); assertEquals(PunishmentPolicy.Action.NONE,p.decide(100,true)); }
    @Test void disabledBanDoesNotDisableKick() { var p=new PunishmentPolicy(false,true,10,true,false,20,true); assertEquals(PunishmentPolicy.Action.KICK,p.decide(11,true)); }
    @Test void kickAndBanSettingsRemainIndependent() { var kickOnly=new PunishmentPolicy(false,true,10,true,false,20,true); var banOnly=new PunishmentPolicy(false,false,10,true,true,20,true); assertEquals(PunishmentPolicy.Action.KICK,kickOnly.decide(11,true)); assertEquals(PunishmentPolicy.Action.NONE,banOnly.decide(11,true)); assertEquals(PunishmentPolicy.Action.BAN,banOnly.decide(21,true)); }
    @Test void unconfirmedEvidenceCannotMeetDefaultSafePolicy() { var p=new PunishmentPolicy(false,true,1,true,true,1,true); assertEquals(PunishmentPolicy.Action.NONE,p.decide(100,false)); }
    @Test void banTakesPrecedenceAfterItsOwnThreshold() { var p=new PunishmentPolicy(false,true,5,true,true,10,true); assertEquals(PunishmentPolicy.Action.KICK,p.decide(7,true)); assertEquals(PunishmentPolicy.Action.BAN,p.decide(10,true)); }
    @Test void permanentBanRequiresTwoIndependentConfirmedChecks() { assertFalse(PunishmentPolicy.permitsPermanentBan(true,1)); assertFalse(PunishmentPolicy.permitsPermanentBan(false,4)); assertTrue(PunishmentPolicy.permitsPermanentBan(true,2)); }
}
