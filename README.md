# Open Mine

Spatial AI workspace foundation.

The Android app uses a portrait orbital HUD with AI Models, Projects, Connectors,
and Knowledge views, a persistent navigation rail and dock, selectable object
cards, detail tabs, and saved context selection. The interface keeps the Open Mine
name and uses the supplied HUD references for composition and artwork.

The Knowledge **Documents** and **Chunks** tabs open the existing object vault.
Object creation, `.omd` import, strict validation, indexing, retrieval, and object
inspection remain native Compose workflows. Settings controls hub animation and
navigation haptics. Provider objects describe available configurations; external
connections and model inference are not established by selecting a card.

Build with Java 17, Android SDK 35, and Gradle 8.11.1:

```sh
gradle :app:assembleDebug
```

GitHub Actions compiles the app, creates a debug APK, and runs device tests for
navigation, selection and context persistence, vault access, and settings. The
`Open-Mine-HUD-verification` artifact includes screenshots of all four main views.

The bundled Ubuntu fonts are distributed under the Ubuntu Font Licence; see
`app/src/main/assets/hud/FONT-LICENSE.txt`. Supplied reference images provide the
detail, central-symbol, and carousel artwork; generated atlases and glass frames
provide the remaining HUD artwork.
