package com.fersaiyan.cyanbridge.ai.image

import com.oudmon.ble.base.communication.ILargeDataResponse
import com.oudmon.ble.base.communication.LargeDataHandler

/** Adapter for the bundled glasses_sdk_20250723_v01 AAR's public response map. */
internal class HeyCyanThumbnailTransport : BleThumbnailTransfer.Transport {
    private var ownedResponse: ILargeDataResponse<*>? = null

    override fun request(callback: (Int, Boolean, ByteArray?) -> Unit) {
        val handler = LargeDataHandler.getInstance()
        check(handler.respMap[THUMBNAIL_SLOT] == null) { "Thumbnail SDK slot already occupied; reconnect glasses" }
        try {
            handler.getPictureThumbnails { type, complete, data -> callback(type, complete, data) }
        } finally {
            ownedResponse = handler.respMap[THUMBNAIL_SLOT]
        }
    }

    override fun detach() {
        val response = ownedResponse ?: return
        LargeDataHandler.getInstance().respMap.remove(THUMBNAIL_SLOT, response)
        ownedResponse = null
    }

    private companion object {
        const val THUMBNAIL_SLOT = -3
    }
}
