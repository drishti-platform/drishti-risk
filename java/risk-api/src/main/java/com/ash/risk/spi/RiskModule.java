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

import com.ash.engine.Descriptor;
import java.util.List;
import java.util.Map;

/**
 * An engine module of the product (design 4.2): {@code risk-market}, {@code risk-ccr}, later liquidity and climate. A JAR that implements
 * this and passes the kit; nothing in the kernel names a module, and a runtime lists the modules it loads.
 */
public interface RiskModule {

    /** {@code market}, {@code ccr}. */
    String id();

    String version();

    /** The measures it offers, by id. */
    List<Measure<?>> measures();

    /** The contracts it reads ({@code risk/position@1}, …) and the outputs it writes, as contract names. */
    List<String> reads();

    List<String> writes();

    /** Its options, declared as the engine descriptor declares them (types, defaults, words). */
    Map<String, Descriptor.Typed> options();

    /** The limit rules of its figures. */
    default List<LimitRule> limits() {
        return List.of();
    }

    /** The reports it defines over its measures. */
    default List<ReportDefinition> reports() {
        return List.of();
    }
}
