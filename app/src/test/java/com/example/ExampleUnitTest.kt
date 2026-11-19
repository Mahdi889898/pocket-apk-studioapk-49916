package com.example

import com.example.engine.TsxModuleTranspiler
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ExampleUnitTest {

    @Test
    fun `transpiles AI Studio TSX React Three Fiber module cleanly`() {
        val tmpDir = createTempDirectory("aistudio_tsx_test").toFile()
        try {
            File(tmpDir, "index.css").writeText("body { margin: 0; }")
            File(tmpDir, "types.ts").writeText(
                """
                export interface GameConfig {
                  speed: number;
                  title?: string;
                }
                export enum PlayState {
                  Idle,
                  Running,
                  Over = 'OVER'
                }
                """.trimIndent()
            )
            File(tmpDir, "App.tsx").writeText(
                """
                import React, { useState, type FC } from 'react';
                import { PlayState, type GameConfig } from './types';
                import './index.css';

                interface Props {
                  initialScore: number;
                }

                export const App: FC<Props> = ({ initialScore }) => {
                  const [score, setScore] = useState<number>(initialScore);
                  const [state, setState] = useState<PlayState>(PlayState.Idle);
                  const cfg = { speed: 12 } as GameConfig;
                  return (
                    <div className="hud">
                      <>
                        <h1>Neon 3D</h1>
                        <button onClick={() => setScore(score + 1)}>Score: {score}</button>
                      </>
                    </div>
                  );
                };
                export default App;
                """.trimIndent()
            )
            File(tmpDir, "index.tsx").writeText(
                """
                import React from 'react';
                import ReactDOM from 'react-dom/client';
                import App from './App';

                const rootEl = document.getElementById('root')!;
                ReactDOM.createRoot(rootEl).render(<App initialScore={5} />);
                """.trimIndent()
            )
            File(tmpDir, "index.html").writeText(
                """
                <!DOCTYPE html>
                <html>
                <head><title>3D Game</title></head>
                <body>
                  <div id="root"></div>
                  <script type="module" src="/index.tsx"></script>
                </body>
                </html>
                """.trimIndent()
            )

            val report = TsxModuleTranspiler.processStagedProject(tmpDir, "index.html")
            assertTrue("Expected 3 transpiled files", report.transpiledFileCount == 3)
            assertTrue("Expected warnings empty, got: ${report.warnings}", report.warnings.isEmpty())

            val html = File(tmpDir, "index.html").readText()
            assertTrue("index.html should reference ./index.js", html.contains("./index.js"))
            assertTrue("index.html should include process polyfill", html.contains("window.process"))

            val appJs = File(tmpDir, "App.js").readText()
            assertFalse("interface should be stripped", appJs.contains("interface Props"))
            assertFalse("type import should be stripped", appJs.contains("type FC"))
            assertFalse("generic call <number> should be stripped", appJs.contains("useState<number>"))
            assertFalse("as GameConfig should be stripped", appJs.contains("as GameConfig"))
            assertTrue("JSX should be lowered to React.createElement", appJs.contains("React.createElement(\"div\""))
            assertTrue("CSS import should point to css-proxy.js", appJs.contains("index.css.css-proxy.js"))
            assertTrue("CSS proxy file should exist", File(tmpDir, "index.css.css-proxy.js").exists())

            val typesJs = File(tmpDir, "types.js").readText()
            assertTrue("enum should lower to JS object", typesJs.contains("export const PlayState = { Idle: 0, Running: 1, Over: 'OVER' }"))

            val indexJs = File(tmpDir, "index.js").readText()
            assertTrue("extensionless ./App import should resolve to ./App.js", indexJs.contains("./App.js"))
            assertFalse("non-null ! should be stripped", indexJs.contains("')!"))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `handles import type, export type, JSX apostrophes, ternaries, and generic arrows without syntax errors`() {
        val tmpDir = createTempDirectory("aistudio_tsx_regression").toFile()
        try {
            File(tmpDir, "types.ts").writeText(
                """
                export type GamePhase =
                  | { kind: 'menu'; high: number }
                  | { kind: 'play'; speed: number };

                export interface Obstacle {
                  id: string;
                  lane: number;
                }

                export const enum Lane {
                  Left = -1,
                  Center = 0,
                  Right = 1
                }
                """.trimIndent()
            )
            File(tmpDir, "reexports.ts").writeText(
                """
                export type { GamePhase, Obstacle } from './types';
                export { Lane, type Obstacle as RoadObstacle } from './types';
                """.trimIndent()
            )
            File(tmpDir, "Hud.tsx").writeText(
                """
                import React, { useState } from 'react';
                import type { GamePhase, Obstacle } from './types';
                import type DefaultPhase from './types';
                import { Lane } from './reexports';

                interface HudProps {
                  active: boolean;
                  items: Obstacle[];
                }

                class CarPhysics {
                  speed!: number;
                  turbo?: boolean;
                  update(dt: number): void {
                    this.speed = dt * 60;
                  }
                }

                const identity = <T,>(val: T): T => val;

                export const Hud = ({ active: isActive, items }: HudProps) => {
                  const [msg, setMsg] = useState<string>("Ready");
                  const primary = "neon";
                  const secondary = "muted";
                  const mode = isActive ? primary : secondary;
                  const ratio = 10 < 20 && 30 > (5 + 5);

                  return (
                    <div className="hud-root
                      flex-col
                      text-white">
                      {/* Main title with apostrophe & slash */}
                      <h1>Tehran's 3D Dash 60 / 120 FPS</h1>
                      <p>Don't hit barriers! Let's press "Start" now.</p>
                      <div>
                        {items.map((o) => (
                          <span key={o.id}>Lane's #{o.lane} ({mode})</span>
                        ))}
                      </div>
                      <button onClick={() => setMsg("Let's go!")}>{identity("Play")}</button>
                    </div>
                  );
                };
                """.trimIndent()
            )
            File(tmpDir, "index.html").writeText(
                """
                <!DOCTYPE html>
                <html><head></head><body><div id="root"></div><script type="module" src="/Hud.tsx"></script></body></html>
                """.trimIndent()
            )

            val report = TsxModuleTranspiler.processStagedProject(tmpDir, "index.html")
            assertTrue("Expected no transpiler warnings, got: ${report.warnings}", report.warnings.isEmpty())

            val reexportsJs = File(tmpDir, "reexports.js").readText()
            assertFalse("export type ... from must not leave dangling 'from'", reexportsJs.contains("from './types'"))
            assertFalse("type modifier inside export { ... } must be stripped", reexportsJs.contains("RoadObstacle"))
            assertTrue("value export Lane must remain", reexportsJs.contains("export { Lane } from \"./types.js\""))

            val hudJs = File(tmpDir, "Hud.js").readText()
            assertFalse("import type ... from must not leave dangling 'from'", hudJs.contains("from './types'"))
            assertFalse("generic arrow prefix <T,> must be stripped", hudJs.contains("<T,>"))
            assertFalse("class definite assignment !: must be stripped", hudJs.contains("speed!"))
            assertFalse("class optional ?: must be stripped", hudJs.contains("turbo?"))
            assertTrue("ternary must preserve ': secondary'", hudJs.contains("isActive ? primary : secondary"))
            assertTrue("comparison expression must not be stripped as generic", hudJs.contains("10 < 20 && 30 > (5 + 5)"))
            assertTrue("destructured rename active: isActive must be preserved", hudJs.contains("active: isActive"))
            assertTrue("JSX apostrophe text must become escaped JS string", hudJs.contains("\"Tehran's 3D Dash 60 / 120 FPS\""))
            assertTrue("JSX second apostrophe text must stay intact", hudJs.contains("Don't hit barriers! Let's press \\\"Start\\\" now."))
            assertTrue("nested map apostrophe JSX must stay intact", hudJs.contains("\"Lane's #\""))
            assertTrue("button onClick after apostrophe JSX must stay intact", hudJs.contains("setMsg(\"Let's go!\")"))
            assertFalse("multiline className must escape newlines", hudJs.contains("className: \"hud-root\n"))
            assertTrue("multiline className must emit escaped newline", hudJs.contains("hud-root\\n"))
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
