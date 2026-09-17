package io.clarionchain.keel.data

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

data class DisplayPrice(
    val currency: String,
    val usdPerBtc: BigDecimal,
    val unitsPerBtc: BigDecimal,
    val source: String,
    val asOfEpochMs: Long,
)

class PriceRepository {
    fun fetch(currency: String): DisplayPrice {
        val code = currency.uppercase()
        val url = URL(
            "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=usd,$code&include_last_updated_at=true",
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TimeUnit.SECONDS.toMillis(15).toInt()
            readTimeout = TimeUnit.SECONDS.toMillis(15).toInt()
            requestMethod = "GET"
        }
        try {
            val body = connection.inputStream.bufferedReader().readText()
            val btc = JSONObject(body).getJSONObject("bitcoin")
            val usd = BigDecimal(btc.get("usd").toString())
            val local = BigDecimal(btc.get(code.lowercase()).toString())
            val updated = if (btc.has("last_updated_at")) btc.getLong("last_updated_at") * 1000L else System.currentTimeMillis()
            return DisplayPrice(
                currency = code,
                usdPerBtc = usd,
                unitsPerBtc = local,
                source = "CoinGecko",
                asOfEpochMs = updated,
            )
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        val CURRENCIES = listOf(
            "USD", "EUR", "GBP", "JPY", "CAD", "AUD", "CHF", "KRW", "BRL", "INR",
        )
    }
}

fun SatsFiatLabel(sats: Long, price: DisplayPrice?): String {
    if (price == null) return ""
    val btc = BigDecimal(sats).divide(BigDecimal(100_000_000L), 8, RoundingMode.DOWN)
    val fiat = btc.multiply(price.unitsPerBtc).setScale(2, RoundingMode.HALF_UP)
    return "${fiat.toPlainString()} ${price.currency}"
}
