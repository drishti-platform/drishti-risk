/*
 * Project Drishti · Any data. Any domain. One grammar.
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
package com.ash.risk.kernel;

import com.ash.engine.EngineException.Kind;

/**
 * The product's refusal and failure codes (RK-2), each an {@code RSK-} code with the engine API's kind. The moved code names a fact by
 * the same word it named it by in Drishti, and the number is Drishti's {@code DRS-} number, so a host maps the family once
 * ({@code RSK-nnnn} to its own code) and an old log line still reads the same. Only the facts the product says are here.
 */
public enum RiskCode {
    /** The request is malformed or names something that is not valid. */
    BAD_REQUEST("RSK-5001", Kind.REFUSED),
    /** Busy protection: a BI run did not start. */
    BUSY_BI_RUN("RSK-9504", Kind.LIMIT),
    /** Busy protection: a bulk load of a pack's data did not start. */
    BUSY_BULK_LOAD("RSK-9505", Kind.LIMIT),
    /** Busy protection: a Data view download did not start; the selection is kept. */
    BUSY_DOWNLOAD("RSK-9503", Kind.LIMIT),
    /** Busy protection: an Arrow Flight read did not start (Arrow memory of the process near its cap, or the server very busy); retry later. */
    BUSY_FLIGHT("RSK-9506", Kind.LIMIT),
    /** Busy protection: a business date cannot be opened just yet (memory, the loads running, the open dates' memory, disk); open dates keep working. */
    BUSY_LOAD("RSK-9502", Kind.LIMIT),
    /** Busy protection: a large pivot view (more cells than {@code drishti.pivot.busy-cells}) did not start (bi/PIVOT.md). */
    BUSY_PIVOT_VIEW("RSK-9507", Kind.LIMIT),
    /** Busy protection (docs/guides/OPERATIONS_GUIDE.md, "Busy protection"): the server is very busy, a new sign-in waits; people signed in keep working. */
    BUSY_SIGN_IN("RSK-9501", Kind.LIMIT),
    /** A kind is routed to a connector (by a pack) for which no connector file or definition exists. */
    CONNECTOR_NOT_CONFIGURED("RSK-1011", Kind.REFUSED),
    /** Content store: a key that is not a valid content key (empty, too long, a {@code ..} segment, control characters). */
    CONTENT_BAD_KEY("RSK-5058", Kind.REFUSED),
    /** Content store (docs/architecture/DATA_ARCHITECTURE.md): the item changed elsewhere since it was read (or already exists): reload and try again. */
    CONTENT_CONFLICT("RSK-5057", Kind.REFUSED),
    /** The free-space guard (2.9.7, CLUSTER_GUIDE.md section 7.2): a hot file, shard book or currency's book was not built because the disk would fall under {@code drishti.disk.min-free-percent} / {@code min-free-gb} even after every folder freed space down to 90% of its cap. Nothing was written; a date already open keeps answering; the answer says what is free, what was needed and the guard. */
    DISK_GUARD("RSK-5218", Kind.UNAVAILABLE),
    /** A compute engine is not running on this server and no other server of the cluster runs it (docs/guides/ADMIN_GUIDE.md section 5.1): its API answers 404 in words, never 403 (nothing is forbidden; the engine is not here). */
    ENGINE_OFF("RSK-1020", Kind.REFUSED),
    /** Stable error codes, {@code DRS-nnnn}. The first digit groups them: 1 sources and data, 2 Sutra, 3 inference, 4 engine and graph, 5 API, 6 identity, 7 collaboration (shares, threads, the inbox), 8 embedded views. Codes are never reused. / public enum RiskCode { /** The entity asked for is not held by any source. */
    ENTITY_NOT_FOUND("RSK-1001", Kind.REFUSED),
    /** Exposure: a bad scope word or parameter ({@code q}, {@code collateral}, {@code date} not a grid date, {@code by}, {@code run}). */
    EXPOSURE_BAD_PARAMETER("RSK-5440", Kind.REFUSED),
    /** Exposure: the scope sums more netting sets than an interactive answer may (a bank-wide PFE): the official capture computes it. */
    EXPOSURE_BATCH_JOB("RSK-5443", Kind.UNAVAILABLE),
    /** Exposure: a date's netting-set matrices need more memory than the budget (drishti.ccr.exposure.memory-budget-gb). */
    EXPOSURE_CAPACITY("RSK-5445", Kind.LIMIT),
    /** Exposure: a date's run is inconsistent (its grid, two batch ids, a duplicate row, a trade in two netting sets): the date is not loaded and the date before answers with a warning; asked with {@code strict=true}, the answer is this refusal. */
    EXPOSURE_INCONSISTENT_RUN("RSK-5441", Kind.REFUSED),
    /** Exposure: a row of a date's exposure paths rejected when the date loads (a vector of another length, NaN or infinity, a grid index out of the run's grid); counted and listed in the answer's provenance, never padded. */
    EXPOSURE_ROW_REJECTED("RSK-5444", Kind.REFUSED),
    /** Exposure: a what-if's candidate paths are not of the date's run (another run id, another number of paths or grid dates). */
    EXPOSURE_WHATIF_RUN("RSK-5442", Kind.REFUSED),
    /** The caller is signed in but may not do this. */
    FORBIDDEN("RSK-5002", Kind.NOT_PERMITTED),
    /** The change needs a second person: the proposer may not approve their own proposal. */
    FOUR_EYES("RSK-2007", Kind.NOT_PERMITTED),
    /** No source serves the kind asked for. */
    NO_SOURCE_FOR_KIND("RSK-1002", Kind.REFUSED),
    /** Input over a configured size or count limit (builder samples). */
    PAYLOAD_TOO_LARGE("RSK-5005", Kind.LIMIT),
    /** The pivot engine (bi/PIVOT.md): a view it cannot answer (an unknown source, field or function, a field twice, a bad filter or sort). */
    PIVOT_BAD_VIEW("RSK-5701", Kind.REFUSED),
    /** The proposal changed or was decided since the caller read it. */
    PROPOSAL_CONFLICT("RSK-2006", Kind.REFUSED),
    /** No proposal of that id exists. */
    PROPOSAL_NOT_FOUND("RSK-2005", Kind.REFUSED),
    /** SIMM: the version asked for (or the default) has no installed ISDA parameter file, or its file is refused (SIMM_GUIDE.md section 4): a setup the person can fix, not a source failure. */
    SIMM_NOT_INSTALLED("RSK-5333", Kind.REFUSED),
    /** A source failed while answering a read. */
    SOURCE_FAILED("RSK-1003", Kind.UNAVAILABLE),
    /** An edit made on an older revision of a Design than the server holds (reload, then edit again). */
    STALE_REVISION("RSK-5007", Kind.REFUSED),
    /** VaR: a date's vectors need more memory than the engine's budget (drishti.var.memory-budget-mb): capacity, try later or raise it. */
    VAR_CAPACITY("RSK-5204", Kind.LIMIT),
    /** VaR: the same trade has two P&amp;L vectors on one business date: the date is not loaded. */
    VAR_DUPLICATE_TRADE("RSK-5202", Kind.REFUSED),
    /** VaR and exposure (VAR_GUIDE.md section 15.14): a hierarchy ({@code drishti.var.hierarchies}, a pack's {@code hierarchies:}) names a level column a source kind does not promote: a warning naming the hierarchy, the column, the kind and the fix ({@code promote <COLUMN> in layout.<kind>.columns}); that kind's rows sit under the (Blank) node at that level (the date, every hierarchy and the official capture go on). A row that leaves a level column empty sits under (Blank) too, counted in the date's warnings. */
    VAR_HIERARCHY_COLUMN("RSK-5211", Kind.REFUSED),
    /** VaR: the hierarchies' configuration is not valid (a duplicate id, a reserved or duplicate scope word, no levels, the removed {@code source:} setting, a level column no source promotes, an {@code ids: leaf} hierarchy whose value has two parents on the date): refused at start, or the date is refused, saying why. */
    VAR_HIERARCHY_INVALID("RSK-5213", Kind.REFUSED),
    /** VaR (2.9.5): the tree of a hierarchy the date does not have (the engine's tree endpoint). */
    VAR_HIERARCHY_UNKNOWN("RSK-5214", Kind.REFUSED),
    /** VaR: a date's inputs are not one run (several batch ids, a vector of another scenario set, two scenario sets, a bad scenario set). */
    VAR_INCONSISTENT_DATE("RSK-5203", Kind.REFUSED),
    /** VaR: one source system sent two forms or grains on one date (scenario PVs and P&amp;L vectors, or trades and positions), or a supplied position covers a trade also sent on its own: nothing would say which counts, so the date is refused (an intraday update in another form or grain than the morning run's is refused and listed). */
    VAR_INPUT_MIXED("RSK-5210", Kind.REFUSED),
    /** VaR: a PV record disagrees with its source system's pin ({@code drishti.var.inputs}): refused and listed, naming both. */
    VAR_INPUT_PIN("RSK-5209", Kind.REFUSED),
    /** VaR: a {@code var-limit} row names a node its hierarchy cannot build (the hierarchy is not configured, or the id has more values than the hierarchy has levels): a warning in the provenance with the reason, the row unused. A node its hierarchy can build but with no positions on the date is not this code: it shows as "limit, no positions". */
    VAR_LIMIT_UNKNOWN_NODE("RSK-5212", Kind.REFUSED),
    /** VaR: no FX for a currency on the date's scenarios (none supplied, and the FX history does not cover the scenario days): a trade priced in it is not loaded (listed in the provenance), and a reporting currency without rates is refused. */
    VAR_NO_FX("RSK-5207", Kind.REFUSED),
    /** VaR: a PV record that does not say what it is, or says it wrongly (2.9.4, VAR_GUIDE.md section 15.13): no {@code VALUE_TYPE}, {@code GRAIN}, id, {@code CURRENCY} or {@code SOURCE_SYSTEM}; a {@code VALUE_TYPE} that is not its payload (PVs or a P&amp;L vector); a {@code GRAIN} that is not its id ({@code TRADE_ID} or {@code POSITION_ID}). The record is refused, listed, never guessed. */
    VAR_RECORD_INVALID("RSK-5208", Kind.REFUSED),
    /** VaR: a row of a date's P&amp;L vectors rejected when the date loads (a vector of the wrong length, NaN or infinity in it, not in USD, a failed row with a vector); never padded or loaded in part. Counted and listed in the answer's provenance, not thrown. */
    VAR_ROW_REJECTED("RSK-5201", Kind.REFUSED),
    /** VaR: a trade's scenario PVs (the {@code scenario-pv} kind) hold a scenario that failed to price (a null or NaN PV): the trade is not loaded, never filled with zero. Counted and listed in the answer's provenance with the scenarios, not thrown. */
    VAR_SCENARIO_FAILED("RSK-5206", Kind.REFUSED),
    /** A system basket (VAR_GUIDE.md section 5.7) that cannot be saved or imported as given: an id that is not {@code A-Z 0-9 . _ -}, a type that is not hierarchy, rule or basket, a node no hierarchy holds on the date, a rule that does not read, a position or trade the date does not hold, a date that is not YYYY-MM-DD, an id that exists already; the message names the basket and the reason. */
    VAR_SYSTEM_BASKET_INVALID("RSK-5220", Kind.REFUSED),
    /** {@code PORT:<id>} (or an edit) of a system basket there is none of on the date: {@code PORT <GO>} lists them. */
    VAR_SYSTEM_BASKET_UNKNOWN("RSK-5221", Kind.REFUSED);

    private final String code;
    private final Kind kind;

    RiskCode(String code, Kind kind) {
        this.code = code;
        this.kind = kind;
    }

    /** The code, {@code RSK-nnnn}. */
    public String code() {
        return code;
    }

    /** The engine API's kind of the refusal or failure. */
    public Kind kind() {
        return kind;
    }
}
