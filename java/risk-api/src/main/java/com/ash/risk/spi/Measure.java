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
import com.ash.engine.Filter;
import com.ash.engine.Inputs;
import com.ash.engine.Snapshot;
import com.ash.engine.Table;
import com.ash.risk.Lens;
import com.ash.risk.Scope;
import java.util.Map;

/**
 * One measure (design 4.2): load a date to an immutable book; additive aggregates of a scope or a shard; the figures from an aggregate
 * under a method (the non-linear rule); contributions; history. The kit proves additivity (the parts' aggregates sum to the whole's, bit
 * for bit) and determinism over runs and thread counts. A measure without its {@link Explainer} and {@link Playground} does not pass.
 */
public interface Measure<B extends Book> {

    /** {@code var}, {@code es}, {@code pvar}, {@code exposure}, … */
    String id();

    /** Loads the date's rows into a book; missing inputs are an answer later, never an error here. */
    B load(Snapshot snapshot, Inputs inputs, Control control) throws EngineException;

    /** The additive aggregates of the rows a scope selects within the visibility (a table in the measure's own layout). */
    Table aggregate(B book, Scope scope, Lens lens, Filter visibility, Control control) throws EngineException;

    /** The figures from aggregates under the method switches ({@code rank-rule}: {@code ceil}). */
    Table figures(B book, Table aggregates, Map<String, String> method, Control control) throws EngineException;

    /** Each member's contribution to the scope's figure. */
    Table contributions(B book, Scope scope, Lens lens, Filter visibility, Map<String, String> method, Control control) throws EngineException;

    /** The measure's explanation of a figure. */
    Explainer<B> explainer();

    /** The measure's formula workbook. */
    Playground<B> playground();
}
