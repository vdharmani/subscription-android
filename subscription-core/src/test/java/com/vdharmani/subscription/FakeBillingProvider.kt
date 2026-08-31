package com.vdharmani.subscription

import android.app.Activity
import com.vdharmani.subscription.model.CustomerInfo
import com.vdharmani.subscription.model.Entitlement
import com.vdharmani.subscription.model.ProductType
import com.vdharmani.subscription.model.Receipt
import com.vdharmani.subscription.model.Store
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Minimal in-memory [BillingProvider] for exercising the core's own decision
 * logic without a store or an SDK.
 */
internal class FakeBillingProvider(
    private val customerInfo: Result<CustomerInfo>,
    private val ownedByCurrentAccount: Result<Boolean?> = Result.success(null),
    override val nativeStore: Store = Store.PLAY_STORE,
) : BillingProvider {

    override suspend fun customerInfo(): Result<CustomerInfo> = customerInfo

    override suspend fun restore(): Result<CustomerInfo> = customerInfo

    override suspend fun ownedByCurrentStoreAccount(entitlement: Entitlement): Result<Boolean?> =
        ownedByCurrentAccount

    override suspend fun purchase(
        activity: Activity,
        productId: String,
        productType: ProductType,
    ): Result<Receipt> = Result.failure(UnknownBillingException("not used in these tests"))

    override suspend fun identify(appUserId: String): Result<CustomerInfo> = customerInfo

    override suspend fun logout(): Result<CustomerInfo> = customerInfo

    override fun observeCustomerInfo(): Flow<CustomerInfo> = emptyFlow()
}
