package net.kuafuai.andee.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The window body: one photograph behind everything, the sidebar and the content
 * as glass on it, (the approval and question cards are their own window beside the ball). Its own function so
 * the screenshot harness draws exactly what the app draws.
 */
@Composable
fun AppRoot(nav: Nav) {
    Box(Modifier.fillMaxSize()) {
        BackdropLayer()
        Row(Modifier.fillMaxSize()) {
            Sidebar(nav)
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                // A reading column: on a wide window the conversation stays a comfortable measure.
                val screen = nav.current
                val card = if (screen == "chat") Modifier else Modifier.padding(14.dp).frostedCard()
                Box(Modifier.widthIn(max = 900.dp).fillMaxHeight().then(card)) {
                    when {
                        screen == "settings" -> SettingsScreen(
                            onDone = { nav.back() }, onOpenGear = { nav.push("gear") },
                            onOpenVault = { nav.push("vault") }, onOpenExpressions = { nav.push("expressions") },
                        )
                        screen == "selfcheck" -> SelfCheckCard(onDone = { nav.back() }, onOpenSettings = { nav.push("settings") })
                        screen == "notebook" -> NotebookCard(onDone = { nav.back() })
                        // `notebook:<tab>` opens on that tab; only the screenshot harness names one.
                        screen.startsWith("notebook:") -> NotebookCard(onDone = { nav.back() }, startTab = screen.substringAfter(':').toIntOrNull() ?: 0)
                        screen == "vault" -> VaultCard(onDone = { nav.back() })
                        screen == "scenes" -> ScenesCard(onDone = { nav.back() })
                        screen == "expressions" -> ExpressionsCard(onDone = { nav.back() })
                        screen == "gear" -> GearCard(onDone = { nav.back() })
                        screen == "peers" -> PeersCard(onDone = { nav.back() })
                        screen == "artifacts" -> ArtifactsCard(onDone = { nav.back() }, onContinue = { nav.go("chat"); ComposerFocus.pending = true })
                        screen.startsWith("topic:") -> TopicScreen(screen.removePrefix("topic:").toLongOrNull() ?: -1L, onDone = { nav.back() },
                            onContinue = { nav.go("chat"); ComposerFocus.pending = true })
                        else -> ChatScreen(
                            onOpenArtifacts = { nav.go("artifacts") },
                            onOpenTopic = { nav.push("topic:$it") },
                            onOpenSettings = { nav.go("settings") },
                            // 继续话题 from the card itself: the same two steps as on the topic page.
                            onContinueTopic = { Assistant.beginTopic(it); ComposerFocus.pending = true },
                        )
                    }
                }
            }
        }
    }
}
