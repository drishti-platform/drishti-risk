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
package com.ash.risk.kernel.workbook;

import com.ash.tools.xlsx.Sheet;
import com.ash.tools.xlsx.Workbook;
import java.io.OutputStream;
import java.util.List;

/**
 * The formula workbook of an engine answer (ENGINE_SPEC 8.3 rule 5), made of one part per output asked: every part writes its values,
 * values only, as a block of the sheet {@code Data} (the blocks one under the other, a blank row between), then its figures as formulas
 * over its block in a section of the sheet {@code Main}, with the engine's value cached beside each formula and the defined names
 * {@code OUT_<OUTPUT>_<COLUMN>} of its first row. The writer streams, so every part's data is written before any part's formulas. The
 * same request writes the same bytes: the parts are written in the order the request names its outputs.
 */
public final class AnswerBook {

    private AnswerBook() {
    }

    /** One output's share of the workbook. */
    public interface Part {

        /** Writes the block from row {@code first} (its header row); returns the last row written. */
        int data(Sheet data, int first);

        /** Writes the section from row {@code first} of {@code Main} (formulas over the block written by {@link #data}); returns the last row. */
        int main(Workbook wb, Sheet main, int first);
    }

    /** Writes the parts; with none, a workbook that says why there is no answer. */
    public static void write(OutputStream out, List<Part> parts, String noAnswer) {
        try (Workbook wb = Workbook.to(out)) {
            Sheet data = wb.sheet("Data");
            if (parts.isEmpty()) {
                data.row(1).text(0, "No data", "header");
                wb.sheet("Main").row(1).text(0, "No answer: " + noAnswer, "normal");
                return;
            }
            int r = 1;
            for (Part p : parts) {
                r = p.data(data, r) + 2;
            }
            Sheet main = wb.sheet("Main");
            int m = 1;
            for (Part p : parts) {
                m = p.main(wb, main, m) + 2;
            }
        }
    }

    /** A zero-based column index as spreadsheet letters (0: A, 25: Z, 26: AA). */
    public static String letters(int index) {
        StringBuilder s = new StringBuilder();
        int i = index + 1;
        while (i > 0) {
            int m = (i - 1) % 26;
            s.insert(0, (char) ('A' + m));
            i = (i - 1) / 26;
        }
        return s.toString();
    }

    /** {@code Data!$C$5:$C$9}: a column's rows of the sheet Data. */
    public static String range(int col, int fromRow, int toRow) {
        String c = letters(col);
        return "Data!$" + c + "$" + fromRow + ":$" + c + "$" + toRow;
    }

    /** {@code Data!$C$5}: one cell of the sheet Data. */
    public static String cell(int col, int row) {
        return "Data!$" + letters(col) + "$" + row;
    }

    /** The defined name of an output's measure column ({@code OUT_NODE_VAR_VAR}). */
    public static String name(String output, String column) {
        return ("OUT_" + output + "_" + column).toUpperCase(java.util.Locale.ROOT).replace('-', '_');
    }
}
