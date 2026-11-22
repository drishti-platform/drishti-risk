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

import com.ash.engine.Cell;
import com.ash.engine.Control;
import com.ash.engine.EngineException;
import com.ash.engine.Explanation;
import com.ash.engine.Request;

/** A figure's derivation as data (design 4.2): steps whose Rachana-EL formulas recompute the figure from the scope's own numbers. */
@FunctionalInterface
public interface Explainer<B extends Book> {

    Explanation explain(B book, Request request, Cell cell, Control control) throws EngineException;
}
