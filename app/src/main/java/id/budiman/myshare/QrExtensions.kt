package id.budiman.myshare

import android.graphics.Bitmap
import com.google.zxing.common.BitMatrix

fun BitMatrix.toBitmap(): Bitmap {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
    for (x in 0 until width) for (y in 0 until height) bmp.setPixel(x, y, if (get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return bmp
}
