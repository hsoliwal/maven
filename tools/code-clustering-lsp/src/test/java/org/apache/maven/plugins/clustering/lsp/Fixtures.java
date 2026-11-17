/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.clustering.lsp;

/**
 * One small source file per language, the same files the real servers were probed with: the symbol ranges in
 * {@link SymbolMappingTest} are the ones those servers returned for these texts.
 */
final class Fixtures {
    static final String JAVA = """
            package demo;

            public class Calc {
                private int total;

                public Calc(int start) {
                    this.total = start;
                }

                public int sum(int[] values) {
                    int s = 0;
                    for (int v : values) s += v;
                    return s + total;
                }

                public int add(int[] items) {
                    int t = 0;
                    for (int i : items) t += i;
                    return t + total;
                }

                interface Op {
                    int apply(int a, int b);
                }

                enum Mode { FAST, SLOW }
            }
            """;
    static final String CPP = """
            #include <cmath>

            namespace geo {
            struct Point {
                double x;
                double y;
                double norm() const;
            };

            double Point::norm() const {
                return std::sqrt(x * x + y * y);
            }

            double dist(const Point& a, const Point& b) {
                double dx = a.x - b.x;
                double dy = a.y - b.y;
                return std::sqrt(dx * dx + dy * dy);
            }

            double distance(const Point& p, const Point& q) {
                double ex = p.x - q.x;
                double ey = p.y - q.y;
                return std::sqrt(ex * ex + ey * ey);
            }
            }
            """;
    static final String PYTHON = """
            LIMIT = 10


            class Shape:
                def __init__(self, width, height):
                    self.width = width
                    self.height = height

                def area(self):
                    return self.width * self.height


            def total(values):
                s = 0
                for v in values:
                    s += v
                return s


            def sum_all(items):
                t = 0
                for i in items:
                    t += i
                return t
            """;
    static final String RUST = """
            pub struct Point {
                pub x: f64,
                pub y: f64,
            }

            impl Point {
                pub fn new(x: f64, y: f64) -> Self {
                    Point { x, y }
                }

                pub fn norm(&self) -> f64 {
                    (self.x * self.x + self.y * self.y).sqrt()
                }
            }

            pub fn dist(a: &Point, b: &Point) -> f64 {
                let dx = a.x - b.x;
                let dy = a.y - b.y;
                (dx * dx + dy * dy).sqrt()
            }

            pub fn distance(p: &Point, q: &Point) -> f64 {
                let ex = p.x - q.x;
                let ey = p.y - q.y;
                (ex * ex + ey * ey).sqrt()
            }

            pub trait Shape {
                fn area(&self) -> f64;
            }

            pub enum Mode {
                Fast,
                Slow,
            }
            """;
    static final String XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
              <name>demo</name>
              <dependencies>
                <dependency>
                  <groupId>org.example</groupId>
                  <artifactId>lib</artifactId>
                </dependency>
              </dependencies>
              <build>
                <plugins>
                  <plugin id="p1"/>
                </plugins>
              </build>
            </project>
            """;
    static final String HTML = """
            <!DOCTYPE html>
            <html>
            <head>
              <title>Demo</title>
            </head>
            <body>
              <div id="main" class="card">
                <ul>
                  <li>One</li>
                  <li>Two</li>
                </ul>
              </div>
              <script>
                function greet(name) { return "hi " + name; }
              </script>
            </body>
            </html>
            """;
    static final String CSS = """
            body {
              margin: 0;
              color: #333;
            }

            .card {
              padding: 8px;
            }

            @media (max-width: 600px) {
              .card .title {
                font-size: 12px;
              }
            }
            """;
    static final String JSON = """
            {
              "name": "demo",
              "version": "1.0.0",
              "scripts": {
                "build": "tsc",
                "test": "node test.js"
              },
              "dependencies": {
                "left-pad": "1.3.0"
              },
              "files": ["dist", "lib"]
            }
            """;

    private Fixtures() { }
}
