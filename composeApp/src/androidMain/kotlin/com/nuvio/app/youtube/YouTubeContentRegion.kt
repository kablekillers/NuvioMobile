package com.nuvio.app.youtube

import android.content.Context
import com.liskovsoft.googlecommon.common.locale.LocaleManager

/** Which country's music YouTube should show: the device's own region. */
internal object YouTubeContentRegion {
    /** Applies the region before the YouTube feeds load. */
    fun apply(@Suppress("UNUSED_PARAMETER") context: Context) {
        LocaleManager.setCountryOverride(null)
    }
}
