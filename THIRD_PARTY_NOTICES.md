# Third-party notices

Andee bundles the following third-party components. The list is generated, not hand-written — it enumerates the **resolved** dependency graph, so it includes transitive dependencies.

Regenerate it with:

```bash
./gradlew :app:dependencies --configuration debugRuntimeClasspath > /tmp/deps-runtime.txt
./gradlew :app:dependencies --configuration debugCompileClasspath  > /tmp/deps-compile.txt
python3 tools/license_audit.py /tmp/deps-runtime.txt /tmp/deps-compile.txt
```

Licenses are read from each artifact's POM. Four artifacts declare nothing in their POM, and one inherits it from a parent; those were verified against the upstream repository instead and are included on that basis.

**Test-only dependencies are not listed here** because they are not shipped: `junit:junit:4.13.2` (EPL-1.0), `androidx.test.espresso:espresso-core:3.5.1` (Apache-2.0), `androidx.test.ext:junit:1.1.5` (Apache-2.0).

---

## Summary — 93 components

| License | Count | What it requires |
|---|---|---|
| Apache-2.0 | 79 | Permissive. Requires keeping the license text and any `NOTICE` file, and stating what you changed. |
| ML Kit Terms of Service | 6 | **Not an open-source license.** The barcode-scanning SDK and its model are governed by Google's ML Kit Terms of Service, and the model is fetched at runtime from Google. Confirm this is acceptable for your redistribution. |
| Android SDK License | 4 | **Not an open-source license.** Google's SDK terms govern redistribution of these `play-services-*` stubs. |
| MIT | 3 | Permissive. Requires keeping the copyright notice and license text. |
| BSD-3-Clause | 1 | Permissive. Requires keeping the copyright notice; do not use the project's name to endorse your fork. |

---

## Components by license

### Apache-2.0 (79)

Permissive. Requires keeping the license text and any `NOTICE` file, and stating what you changed.

| Component | License reference |
|---|---|
| `androidx.activity:activity:1.8.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.annotation:annotation-experimental:1.3.1` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.annotation:annotation-jvm:1.8.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.annotation:annotation:1.8.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.appcompat:appcompat-resources:1.6.1` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.appcompat:appcompat:1.6.1` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.arch.core:core-common:2.2.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.arch.core:core-runtime:2.2.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.camera:camera-camera2:1.3.4` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.camera:camera-core:1.3.4` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.camera:camera-lifecycle:1.3.4` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.camera:camera-video:1.3.4` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.camera:camera-view:1.3.4` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.cardview:cardview:1.0.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.collection:collection:1.1.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.concurrent:concurrent-futures:1.1.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.constraintlayout:constraintlayout-solver:2.0.1` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.constraintlayout:constraintlayout:2.0.1` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.coordinatorlayout:coordinatorlayout:1.1.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.core:core-ktx:1.9.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.core:core:1.9.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.cursoradapter:cursoradapter:1.0.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.customview:customview:1.1.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.documentfile:documentfile:1.0.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.drawerlayout:drawerlayout:1.1.1` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.dynamicanimation:dynamicanimation:1.0.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.emoji2:emoji2-views-helper:1.2.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.emoji2:emoji2:1.2.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.exifinterface:exifinterface:1.3.2` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.fragment:fragment:1.3.6` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.interpolator:interpolator:1.0.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.legacy:legacy-support-core-utils:1.0.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.lifecycle:lifecycle-common:2.6.1` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.lifecycle:lifecycle-livedata-core:2.6.1` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.lifecycle:lifecycle-livedata:2.6.1` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.lifecycle:lifecycle-process:2.6.1` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.lifecycle:lifecycle-runtime:2.6.1` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate:2.6.1` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.lifecycle:lifecycle-viewmodel:2.6.1` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.loader:loader:1.0.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.localbroadcastmanager:localbroadcastmanager:1.0.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.print:print:1.0.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.profileinstaller:profileinstaller:1.3.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.recyclerview:recyclerview:1.1.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.resourceinspection:resourceinspection-annotation:1.0.1` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.savedstate:savedstate:1.2.1` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.startup:startup-runtime:1.1.1` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.tracing:tracing:1.0.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.transition:transition:1.2.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.vectordrawable:vectordrawable-animated:1.1.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.vectordrawable:vectordrawable:1.1.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.versionedparcelable:versionedparcelable:1.1.1` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.viewpager2:viewpager2:1.0.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `androidx.viewpager:viewpager:1.0.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `com.google.android.datatransport:transport-api:2.2.1` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `com.google.android.datatransport:transport-backend-cct:2.3.3` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `com.google.android.datatransport:transport-runtime:2.2.6` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `com.google.android.material:material:1.10.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `com.google.auto.value:auto-value-annotations:1.6.3` | https://github.com/google/auto/blob/main/LICENSE |
| `com.google.errorprone:error_prone_annotations:2.15.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `com.google.firebase:firebase-annotations:16.0.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `com.google.firebase:firebase-components:16.1.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `com.google.firebase:firebase-encoders-json:17.1.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `com.google.firebase:firebase-encoders:16.1.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `com.google.guava:listenablefuture:1.0` | https://repo1.maven.org/maven2/com/google/guava/guava-parent/26.0-android/guava-parent-26.0-android.pom |
| `com.squareup.okhttp3:okhttp:4.12.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `com.squareup.okio:okio-jvm:3.6.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `com.squareup.okio:okio:3.6.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `javax.inject:javax.inject:1` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `org.jetbrains.kotlin:kotlin-bom:1.8.22` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `org.jetbrains.kotlin:kotlin-stdlib-common:1.9.22` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.10` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.10` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `org.jetbrains.kotlin:kotlin-stdlib:1.9.22` | http://www.apache.org/licenses/LICENSE-2.0.txt |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4` | https://www.apache.org/licenses/LICENSE-2.0.txt |
| `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.6.4` | https://www.apache.org/licenses/LICENSE-2.0.txt |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.6.4` | https://www.apache.org/licenses/LICENSE-2.0.txt |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4` | https://www.apache.org/licenses/LICENSE-2.0.txt |
| `org.jetbrains:annotations:13.0` | http://www.apache.org/licenses/LICENSE-2.0.txt |

### ML Kit Terms of Service (6)

**Not an open-source license.** The barcode-scanning SDK and its model are governed by Google's ML Kit Terms of Service, and the model is fetched at runtime from Google. Confirm this is acceptable for your redistribution.

| Component | License reference |
|---|---|
| `com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.0` | https://developers.google.com/ml-kit/terms |
| `com.google.mlkit:barcode-scanning-common:17.0.0` | https://developers.google.com/ml-kit/terms |
| `com.google.mlkit:barcode-scanning:17.2.0` | https://developers.google.com/ml-kit/terms |
| `com.google.mlkit:common:18.9.0` | https://developers.google.com/ml-kit/terms |
| `com.google.mlkit:vision-common:17.3.0` | https://developers.google.com/ml-kit/terms |
| `com.google.mlkit:vision-interfaces:16.2.0` | https://developers.google.com/ml-kit/terms |

### Android SDK License (4)

**Not an open-source license.** Google's SDK terms govern redistribution of these `play-services-*` stubs.

| Component | License reference |
|---|---|
| `com.google.android.gms:play-services-base:18.1.0` | https://developer.android.com/studio/terms.html |
| `com.google.android.gms:play-services-basement:18.1.0` | https://developer.android.com/studio/terms.html |
| `com.google.android.gms:play-services-tasks:18.0.2` | https://developer.android.com/studio/terms.html |
| `com.google.android.odml:image:1.0.0-beta1` | https://developer.android.com/studio/terms.html |

### MIT (3)

Permissive. Requires keeping the copyright notice and license text.

| Component | License reference |
|---|---|
| `com.github.mik3y:usb-serial-for-android:3.8.1` | https://github.com/mik3y/usb-serial-for-android/blob/master/LICENSE.txt |
| `org.java-websocket:Java-WebSocket:1.5.7` | https://github.com/TooTallNate/Java-WebSocket/blob/master/LICENSE |
| `org.slf4j:slf4j-api:2.0.6` | https://github.com/qos-ch/slf4j/blob/master/LICENSE.txt |

### BSD-3-Clause (1)

Permissive. Requires keeping the copyright notice; do not use the project's name to endorse your fork.

| Component | License reference |
|---|---|
| `androidx.camera:camera-core:1.3.4` | https://chromium.googlesource.com/libyuv/libyuv/+/refs/heads/main/README.chromium |

---

## Bundled assets

The generator above reads POMs, so it sees **code and no assets**. There is one
asset whose provenance is worth stating rather than leaving to be inferred:

| File | Status |
|---|---|
| `app/src/main/res/drawable-nodpi/backdrop.jpg` | Supplied by the project owner as the unfolded card's background (see `ui/Backdrop.kt`), and licensed with the rest of this tree. |

If you fork this and would rather not redistribute that image, it is one file and
it is replaceable: `Backdrop` derives its scrim from the image's own mean luma, so
a different JPEG at the same path needs no code change. See the `Backdrop` section
of `CLAUDE.md`.

The app icon and the vector drawables are this project's own work. No fonts are
bundled — the CJK stack named in `ToolSchemas`' `show_html` description resolves
against whatever the device has.

