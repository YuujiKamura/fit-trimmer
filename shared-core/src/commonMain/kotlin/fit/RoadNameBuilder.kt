package fit

object RoadNameBuilder {
    fun buildCaptionText(
        rdCtg: String?,
        roadName: String?,
        ref: String?,
        city: String?,
        town: String?,
        village: String?,
        suburb: String?,
        county: String? = null,
        neighbourhood: String? = null,
        countryCode: String? = null,
        state: String? = null
    ): String? {
        val formatter = when (countryCode?.lowercase()) {
            "us" -> UsRoadCaptionFormatter()
            "jp" -> JpRoadCaptionFormatter()
            null, "" -> JpRoadCaptionFormatter()
            else -> FallbackRoadCaptionFormatter()
        }
        return formatter.format(
            rdCtg = rdCtg,
            roadName = roadName,
            ref = ref,
            city = city,
            town = town,
            village = village,
            suburb = suburb,
            county = county,
            neighbourhood = neighbourhood,
            state = state
        )
    }
}
