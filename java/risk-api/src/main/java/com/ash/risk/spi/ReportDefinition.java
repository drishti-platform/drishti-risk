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

import java.util.List;

/** A regulatory or management report as cells over measures (design 4.2): each cell traces to a measure and its inputs. */
public interface ReportDefinition {

    String id();

    String title();

    /** One cell: its reference in the report, the measure, the scope text, the column of the measure's answer. */
    record CellSource(String cell, String measure, String scope, String column) {}

    List<CellSource> cells();
}
