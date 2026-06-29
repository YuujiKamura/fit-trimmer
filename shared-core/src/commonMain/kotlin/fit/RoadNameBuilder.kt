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
        neighbourhood: String? = null
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
        // 日本の住所体系において「市(city)」は「郡(county)」に属さないため、cityが存在する場合はcountyを無視する
        if (city.isNullOrEmpty()) {
            appendArea(county)
        }
        appendArea(city)
        appendArea(suburb)
        appendArea(town)
        appendArea(village)
        appendArea(neighbourhood)
        
        val area = areaBuilder.toString()
        val areaSuffix = if (area.isNotEmpty()) "（$area 付近）" else ""
        
        return "$mainRoadText$areaSuffix"
    }
}
