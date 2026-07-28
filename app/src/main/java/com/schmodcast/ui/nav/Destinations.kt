package com.schmodcast.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector

enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    Library("library", "Library", Icons.Filled.Home),
    Search("search", "Search", Icons.Filled.Search),
}
