package com.instalogger.app.util

import android.content.Context
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import okio.Buffer
import java.io.File

class VaultMediaFetcher(
    private val context: Context,
    private val data: File,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val vaultManager = VaultStorageManager.getInstance(context)
        if (!vaultManager.isVaultFile(data)) {
            return null // Not a vault file, fallback to default Coil fetcher
        }

        val decryptedBytes = vaultManager.readEncryptedFile(data) ?: return null
        val buffer = Buffer().write(decryptedBytes)

        val imageSource = ImageSource(
            source = buffer,
            context = context
        )

        val mimeType = when {
            data.name.endsWith(".mp4.enc", ignoreCase = true) || data.name.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
            data.name.endsWith(".png.enc", ignoreCase = true) || data.name.endsWith(".png", ignoreCase = true) -> "image/png"
            else -> "image/jpeg"
        }

        return SourceResult(
            source = imageSource,
            mimeType = mimeType,
            dataSource = DataSource.DISK
        )
    }

    class Factory(private val context: Context) : Fetcher.Factory<File> {
        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.name.endsWith(".enc", ignoreCase = true) || VaultStorageManager.getInstance(context).isVaultFile(data)) {
                return VaultMediaFetcher(context, data, options)
            }
            return null
        }
    }
}
