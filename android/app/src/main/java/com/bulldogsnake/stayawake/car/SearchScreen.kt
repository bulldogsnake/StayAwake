package com.bulldogsnake.stayawake.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template

/** Uses the car's keyboard template to collect a line of text, then hands it back. */
class SearchScreen(
    carContext: CarContext,
    private val hint: String,
    private val onSubmit: (String) -> Unit,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val callback = object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {}

            override fun onSearchSubmitted(searchText: String) {
                screenManager.pop()
                if (searchText.isNotBlank()) onSubmit(searchText.trim())
            }
        }
        return SearchTemplate.Builder(callback)
            .setHeaderAction(Action.BACK)
            .setSearchHint(hint)
            .setShowKeyboardByDefault(true)
            .build()
    }
}
