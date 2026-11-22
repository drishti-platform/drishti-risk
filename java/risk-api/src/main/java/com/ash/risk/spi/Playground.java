/*
 * Drishti Risk · market and counterparty risk as engines.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.risk.spi;

import com.ash.engine.Control;
import com.ash.engine.EngineException;
import com.ash.engine.Request;
import java.io.IOException;
import java.io.OutputStream;

/**
 * A measure's formula workbook (design 4.2, RK-15): a values-only Data sheet, formula sheets, a drop-down cell per method switch; the
 * stored values equal the engine's, and an outside recalculation agrees (the kit checks both).
 */
@FunctionalInterface
public interface Playground<B extends Book> {

    void write(B book, Request request, OutputStream out, Control control) throws EngineException, IOException;
}
