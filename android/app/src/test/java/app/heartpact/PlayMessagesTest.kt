package app.heartpact

import app.heartpact.billing.PlayMessages
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.OnPurchasesUpdatedSubResponseCode
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayMessagesTest {
    @Test fun `Play Billing 9's specific reasons win over the general code`() {
        assertEquals(PlayMessages.DECLINED, PlayMessages.forCode(BillingResponseCode.ERROR, OnPurchasesUpdatedSubResponseCode.PAYMENT_DECLINED_DUE_TO_INSUFFICIENT_FUNDS))
        assertEquals(PlayMessages.INELIGIBLE, PlayMessages.forCode(BillingResponseCode.ERROR, OnPurchasesUpdatedSubResponseCode.USER_INELIGIBLE))
        assertEquals(PlayMessages.FAILED, PlayMessages.forCode(BillingResponseCode.ERROR, OnPurchasesUpdatedSubResponseCode.NO_APPLICABLE_SUB_RESPONSE_CODE))
    }

    @Test fun `without one, the general code decides`() {
        // A Play Store blocked by the system (a kids mode) answers BILLING_UNAVAILABLE since Play Billing 9.
        assertEquals(PlayMessages.UNAVAILABLE, PlayMessages.forCode(BillingResponseCode.BILLING_UNAVAILABLE))
        assertEquals(PlayMessages.UNREACHABLE, PlayMessages.forCode(BillingResponseCode.NETWORK_ERROR))
        assertEquals(PlayMessages.NOT_ON_SALE, PlayMessages.forCode(BillingResponseCode.ITEM_UNAVAILABLE))
        assertEquals(PlayMessages.FAILED, PlayMessages.forCode(BillingResponseCode.DEVELOPER_ERROR))
    }
}
