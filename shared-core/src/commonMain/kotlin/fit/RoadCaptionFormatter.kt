package fit

interface RoadCaptionFormatter {
    fun format(
        rdCtg: String?,
        roadName: String?,
        ref: String?,
        city: String?,
        town: String?,
        village: String?,
        suburb: String?,
        county: String?,
        neighbourhood: String?,
        state: String?,
        country: String?
    ): String?
}

class JpRoadCaptionFormatter : RoadCaptionFormatter {
    override fun format(
        rdCtg: String?,
        roadName: String?,
        ref: String?,
        city: String?,
        town: String?,
        village: String?,
        suburb: String?,
        county: String?,
        neighbourhood: String?,
        state: String?,
        country: String?
    ): String? {
        val normalizedRoadName = roadName ?: ""
        val normalizedRef = ref ?: ""
        val normalizedCtg = rdCtg ?: ""
        
        var mainRoadText = ""
        
        // 1. Determine road classification prefix
        val categoryPrefix = when (normalizedCtg) {
            "高速自動車国道" -> "高速道路"
            "一般国道" -> "国道"
            "都道府県道" -> "県道"
            "市区町村道等" -> {
                if (town != null && town.isNotEmpty()) "町道"
                else if (village != null && village.isNotEmpty()) "村道"
                else "市道"
            }
            else -> ""
        }
        
        // 2. Build road main text
        if (categoryPrefix.isNotEmpty()) {
            if (categoryPrefix == "国道" || categoryPrefix == "県道") {
                val refNum = if (normalizedRef.isNotEmpty() && normalizedRef.all { it.isDigit() }) {
                    "${categoryPrefix}${normalizedRef}号"
                } else {
                    normalizedRef
                }
                
                if (refNum.isNotEmpty() && normalizedRoadName.isNotEmpty()) {
                    if (normalizedRoadName.contains(refNum) || refNum.contains(normalizedRoadName)) {
                        mainRoadText = normalizedRoadName
                    } else {
                        mainRoadText = "$refNum $normalizedRoadName"
                    }
                } else if (normalizedRoadName.isNotEmpty()) {
                    mainRoadText = "$categoryPrefix $normalizedRoadName"
                } else if (refNum.isNotEmpty()) {
                    mainRoadText = refNum
                }
            } else if (categoryPrefix == "市道" || categoryPrefix == "町道" || categoryPrefix == "村道") {
                if (normalizedRoadName.isNotEmpty()) {
                    mainRoadText = "$categoryPrefix $normalizedRoadName"
                } else {
                    mainRoadText = categoryPrefix
                }
            } else {
                mainRoadText = if (normalizedRoadName.isNotEmpty()) normalizedRoadName else categoryPrefix
            }
        } else {
            // Fallback to legacy matching if rdCtg is not available
            if (normalizedRef.isNotEmpty() && normalizedRoadName.isNotEmpty()) {
                mainRoadText = if (normalizedRoadName.contains(normalizedRef) || normalizedRef.contains(normalizedRoadName)) {
                    normalizedRoadName
                } else {
                    val fallbackPrefix = if (normalizedRoadName.contains("県道")) "県道${normalizedRef}号"
                    else if (normalizedRoadName.contains("国道")) "国道${normalizedRef}号"
                    else "r$normalizedRef"
                    "$fallbackPrefix $normalizedRoadName"
                }
            } else if (normalizedRoadName.isNotEmpty()) {
                mainRoadText = normalizedRoadName
            } else if (normalizedRef.isNotEmpty()) {
                mainRoadText = normalizedRef
            }
        }
        
        if (mainRoadText.isEmpty()) return null
        
        val areaBuilder = StringBuilder()
        fun appendArea(part: String?) {
            if (part.isNullOrEmpty()) return
            if (areaBuilder.contains(part)) return
            areaBuilder.append(part)
        }
        
        val isCountyArea = !town.isNullOrEmpty() || !village.isNullOrEmpty()
        
        if (isCountyArea) {
            // 郡部（町村）の処理: 市(city)や行政区("区")は不整合データとして無視する
            appendArea(county)
            appendArea(town)
            appendArea(village)
            
            // suburbが「区」で終わらない場合（大字・地区名など）のみ結合を許可する
            val isSuburbAdministrativeDistrict = suburb != null && suburb.endsWith("区")
            if (!isSuburbAdministrativeDistrict) {
                appendArea(suburb)
            }
        } else {
            // 市部（政令指定都市・一般市）の処理: 郡(county)は無視する
            appendArea(city)
            appendArea(suburb)
        }
        appendArea(neighbourhood)
        
        val area = areaBuilder.toString()
        val areaSuffix = if (area.isNotEmpty()) "（$area 付近）" else ""
        val prefecturePrefix = if (!state.isNullOrEmpty()) "$state " else ""
        
        return "$prefecturePrefix$mainRoadText$areaSuffix"
    }
}

class UsRoadCaptionFormatter : RoadCaptionFormatter {
    override fun format(
        rdCtg: String?,
        roadName: String?,
        ref: String?,
        city: String?,
        town: String?,
        village: String?,
        suburb: String?,
        county: String?,
        neighbourhood: String?,
        state: String?,
        country: String?
    ): String? {
        val normalizedRoadName = roadName ?: ""
        val normalizedRef = ref ?: ""
        
        // Safety: If there is no clear Route Ref number (e.g. US-101, I-5),
        // we treat the local road name as uncertain to avoid incorrect/noisy snapping.
        // We only show the road name if it is confirmed by a route reference number.
        var mainRoad = ""
        if (normalizedRef.isNotEmpty()) {
            if (normalizedRoadName.isNotEmpty()) {
                mainRoad = "$normalizedRef $normalizedRoadName"
            } else {
                mainRoad = normalizedRef
            }
        }
        
        val parts = mutableListOf<String>()
        val localCity = city ?: town ?: village ?: ""
        if (localCity.isNotEmpty()) parts.add(localCity)
        state?.let { parts.add(it) }
        val resolvedCountry = country ?: "USA"
        parts.add(resolvedCountry)
        
        val areaText = parts.filter { it.isNotEmpty() }.joinToString(", ")
        
        return if (mainRoad.isNotEmpty()) {
            "$mainRoad ($areaText)"
        } else {
            areaText // Fallback to just "City, State, Country" if the local road name is uncertain
        }
    }
}

class FallbackRoadCaptionFormatter : RoadCaptionFormatter {
    override fun format(
        rdCtg: String?,
        roadName: String?,
        ref: String?,
        city: String?,
        town: String?,
        village: String?,
        suburb: String?,
        county: String?,
        neighbourhood: String?,
        state: String?,
        country: String?
    ): String? {
        val normalizedRoadName = roadName ?: ""
        val normalizedRef = ref ?: ""
        
        // Safety: Only display the road name if it is a major roadway verified by a ref number (e.g. A7, D906).
        // Otherwise, omit the uncertain road name to prevent false snapping info, showing only the region and country.
        var mainRoad = ""
        if (normalizedRef.isNotEmpty()) {
            if (normalizedRoadName.isNotEmpty()) {
                mainRoad = "$normalizedRef $normalizedRoadName"
            } else {
                mainRoad = normalizedRef
            }
        }
        
        val parts = mutableListOf<String>()
        val localCity = city ?: town ?: village ?: ""
        if (localCity.isNotEmpty()) parts.add(localCity)
        state?.let { parts.add(it) }
        country?.let { parts.add(it) }
        
        val areaText = parts.filter { it.isNotEmpty() }.joinToString(", ")
        
        return if (mainRoad.isNotEmpty()) {
            if (areaText.isNotEmpty()) "$mainRoad ($areaText)" else mainRoad
        } else {
            areaText.ifEmpty { null } // Fallback to just "City, State, Country" if road is uncertain
        }
    }
}
