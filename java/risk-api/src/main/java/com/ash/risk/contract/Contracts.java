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
package com.ash.risk.contract;

import java.util.List;

/**
 * The names of the contracts the product reads and writes (design section 3), in the Lake contract form {@code <owner>/<name>@<major>}.
 * The definitions (YAML) live in {@code contracts/} of this repository; kind names are today's, so no data file changes.
 */
public final class Contracts {

    private Contracts() {
    }

    public static final String POSITION = "risk/position@1";
    public static final String PNL_VECTOR = "risk/pnl-vector@1";
    public static final String SCENARIO_PV = "risk/scenario-pv@1";
    public static final String SCENARIO_SET = "risk/scenario-set@1";
    public static final String FX_SCENARIO = "risk/fx-scenario@1";
    public static final String POSITION_SENSITIVITY = "risk/position-sensitivity@1";
    public static final String FRTB_SENSITIVITY = "risk/frtb-sensitivity@1";
    public static final String RISK_FACTOR = "risk/risk-factor@1";
    public static final String DESK_PNL = "risk/desk-pnl@1";
    public static final String PNL_HISTORY = "risk/pnl-history@1";
    public static final String STRESS_SCENARIO = "risk/stress-scenario@1";
    public static final String STRESS_SCENARIO_PNL = "risk/stress-scenario-pnl@1";
    public static final String EXPOSURE_PATH = "risk/exposure-path@1";
    public static final String SIMULATION_RUN = "risk/simulation-run@1";
    public static final String NETTING_SET = "risk/netting-set@1";
    public static final String CSA = "risk/csa@1";
    public static final String AGREEMENT = "risk/agreement@1";
    public static final String COLLATERAL_BALANCE = "risk/collateral-balance@1";
    public static final String TRADE_VALUATION = "risk/trade-valuation@1";
    public static final String NSET_MTM = "risk/nset-mtm@1";
    public static final String CPTY_MTM = "risk/cpty-mtm@1";
    public static final String VAR_LIMIT = "risk/var-limit@1";
    public static final String CREDIT_LIMIT = "risk/credit-limit@1";

    /** The outputs, stored by a host under these contracts. */
    public static final String VAR_RESULT = "risk/var-result@1";
    public static final String VAR_OFFICIAL = "risk/var-official@1";
    public static final String EXPOSURE_RESULT = "risk/exposure-result@1";
    public static final String EXPOSURE_OFFICIAL_CPTY = "risk/exposure-official-cpty@1";
    public static final String STRESS_RESULT = "risk/stress-result@1";

    /** Every input contract, in the order of design section 3. */
    public static List<String> inputs() {
        return List.of(POSITION, PNL_VECTOR, SCENARIO_PV, SCENARIO_SET, FX_SCENARIO, POSITION_SENSITIVITY, FRTB_SENSITIVITY, RISK_FACTOR,
                DESK_PNL, PNL_HISTORY, STRESS_SCENARIO, STRESS_SCENARIO_PNL, EXPOSURE_PATH, SIMULATION_RUN, NETTING_SET, CSA, AGREEMENT,
                COLLATERAL_BALANCE, TRADE_VALUATION, NSET_MTM, CPTY_MTM, VAR_LIMIT, CREDIT_LIMIT);
    }

    /** Every output contract. */
    public static List<String> outputs() {
        return List.of(VAR_RESULT, VAR_OFFICIAL, EXPOSURE_RESULT, EXPOSURE_OFFICIAL_CPTY, STRESS_RESULT);
    }
}
