package app.heartpact.screens

import app.heartpact.data.Appointment
import app.heartpact.data.CycleDayLog
import app.heartpact.data.CyclePrediction
import app.heartpact.data.CycleProfile
import app.heartpact.data.CycleShare
import app.heartpact.data.FlowLevel
import app.heartpact.data.KickSession
import app.heartpact.data.Pregnancy
import app.heartpact.data.SharedCycle
import app.heartpact.ui.cycle.CycleActions
import app.heartpact.ui.cycle.CycleMode
import app.heartpact.ui.cycle.CycleScreen
import app.heartpact.ui.cycle.CycleUi
import app.heartpact.ui.pregnancy.PregnancyActions
import app.heartpact.ui.pregnancy.PregnancyScreen
import app.heartpact.ui.pregnancy.PregnancyUi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class CycleShotsTest {
    private val today = LocalDate.of(2026, 10, 11)
    private val prediction = CyclePrediction("2026-10-28", "2026-10-09", "2026-11-01", "2026-10-14", "2026-09-30", 12)
    private val logs = (listOf("2026-09-30", "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04").map { CycleDayLog(it, FlowLevel.Medium) } +
        CycleDayLog("2026-10-11", FlowLevel.None, listOf("Tired")))

    @Test fun cycle() {
        shot("cycle") { CycleScreen(CycleUi(CycleMode.Owner, CycleProfile(shareLevel = CycleShare.PredictionsOnly, lastPeriodStartDate = "2026-09-30"), prediction, logs), "Theo", CycleActions(), today) }
        shot("cycle-setup") { CycleScreen(CycleUi(CycleMode.Setup), "Theo", CycleActions(), today) }
        shot("cycle-partner") { CycleScreen(CycleUi(CycleMode.Partner, shared = SharedCycle(CycleProfile(shareLevel = CycleShare.PredictionsOnly), prediction)), "Maya", CycleActions(), today) }
        val late = prediction.copy(lastPeriodStart = "2026-09-30", cycleDay = 32, daysLate = 3)
        shot("cycle-late") { CycleScreen(CycleUi(CycleMode.Owner, CycleProfile(lastPeriodStartDate = "2026-09-30"), late, logs.take(5)), "Theo", CycleActions(), LocalDate.of(2026, 10, 31)) }
        shot("cycle-settings") { CycleScreen(CycleUi(CycleMode.Setup, CycleProfile(shareLevel = CycleShare.PredictionsOnly, lastPeriodStartDate = "2026-09-30"), prediction, editing = true), "Theo", CycleActions(), today) }
    }

    @Test fun pregnancy() {
        val p = Pregnancy("p", "maya", "Maya", isMine = true, dueDate = "2027-03-14", week = 18, day = 3, trimester = 2, daysToGo = 154, progress = 0.45)
        val appts = listOf(
            Appointment("a", "p", "Anatomy scan", "2026-10-21T09:30:00Z", "City Hospital", createdByUserId = "maya"),
            Appointment("b", "p", "Antenatal class", "2026-10-23T18:00:00Z", createdByUserId = "theo"),
        )
        val kicks = listOf(KickSession("k", "p", "2026-10-10T13:10:00Z", "2026-10-10T13:50:00Z", 7, 2400))
        shot("pregnancy") { PregnancyScreen(PregnancyUi(p, appts, kicks, loaded = true), "maya", "Theo", PregnancyActions(), today) }
        shot("pregnancy-start") { PregnancyScreen(PregnancyUi(loaded = true), "maya", "Theo", PregnancyActions(), today) }
        val ended = p.copy(status = app.heartpact.data.PregnancyStatus.Ended, shareWithPartner = false, endedAtUtc = "2026-10-01T10:00:00Z")
        shot("pregnancy-ended") { PregnancyScreen(PregnancyUi(loaded = true, ended = listOf(ended)), "maya", "Theo", PregnancyActions(), today) }
        shot("pregnancy-kicks") { PregnancyScreen(PregnancyUi(p, appts, kicks, counting = kicks.first().copy(endedAtUtc = null), kickCount = 4, loaded = true), "maya", "Theo", PregnancyActions(), today) }
    }
}
