package org.multipaz.samples.wallet.cmp.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.multipaz.provisioning.ProvisioningModel
import org.multipaz.util.Logger

private const val TAG = "UrlHandler"
private const val OID4VCI_CREDENTIAL_OFFER_URL_SCHEME = "openid-credential-offer://"
private const val HAIP_URL_SCHEME = "haip://"

/**
 * Handle a link (either an app link, universal link, or custom URL scheme link).
 * This is a common handler that can be used from both Android and iOS.
 *
 * @param url The URL to handle
 * @param credentialOffers Channel to send credential offers to
 * @param provisioningModel The provisioning model instance
 * @param provisioningSupport The provisioning support instance
 */
fun handleUrl(
    url: String,
    credentialOffers: Channel<String>,
    provisioningModel: ProvisioningModel,
    provisioningSupport: ProvisioningSupport,
) {
    if (url.startsWith(OID4VCI_CREDENTIAL_OFFER_URL_SCHEME) || url.startsWith(HAIP_URL_SCHEME)) {
        // Process credential offers
        val queryIndex = url.indexOf('?')
        if (queryIndex >= 0) {
            CoroutineScope(Dispatchers.Default).launch {
                credentialOffers.send(url)
            }
        }
    } else if (url.startsWith(ProvisioningSupport.APP_LINK_BASE_URL)) {
        // Process OAuth callbacks
        CoroutineScope(Dispatchers.Default).launch {
            try {
                provisioningSupport.processAppLinkInvocation(url)
            } catch (e: Exception) {
                Logger.e(TAG, "Error processing app link: ${e.message}", e)
            }
        }
    }
}


