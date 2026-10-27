package com.illini.grades.util;

import com.illini.grades.dto.GradeDistributionDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GpaCalculatorTest {

    @Test
    void computesGpaWithoutCountingWithdrawals() {
        assertEquals(11.0 / 3.0, GpaCalculator.computeGpa(
            1, 1, 0,
            0, 1, 0,
            0, 0, 0,
            0, 0, 0,
            0, 4
        ), 0.0001);
    }

    @Test
    void computesGpaIncludingWithdrawals() {
        assertEquals(1.0, GpaCalculator.computeGpaIncludingW(
            1, 0, 0,
            0, 0, 0,
            0, 0, 0,
            0, 0, 0,
            0, 3
        ), 0.0001);
    }

    @Test
    void emptyGradeCountsHaveUndefinedGpa() {
        GradeDistributionDto empty = GpaCalculator.sum(List.of());
        assertEquals(0, empty.total());
        assertNull(empty.gpa());
        assertNull(empty.gpaIncludingW());
    }

    @Test
    void withdrawalsOnlyHaveNoStandardGpa() {
        GradeDistributionDto withdrawals = GpaCalculator.fromCounts(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3
        );
        assertEquals(3, withdrawals.total());
        assertNull(withdrawals.gpa());
        assertEquals(0.0, withdrawals.gpaIncludingW(), 0.0001);
    }

    @Test
    void actualFailuresHaveZeroGpa() {
        GradeDistributionDto failures = GpaCalculator.fromCounts(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 4, 0
        );
        assertEquals(4, failures.total());
        assertEquals(0.0, failures.gpa(), 0.0001);
        assertEquals(0.0, failures.gpaIncludingW(), 0.0001);
    }

    @Test
    void emptySemestersDoNotAffectHistoricalGpa() {
        GradeDistributionDto empty = GpaCalculator.sum(List.of());
        GradeDistributionDto historical = GpaCalculator.fromCounts(
            1, 0, 0, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0
        );
        assertEquals(historical, GpaCalculator.sum(List.of(empty, historical, empty)));
    }

    @Test
    void sumsGradeCountsAndRecomputesAggregateGpa() {
        GradeDistributionDto first = GpaCalculator.fromCounts(
            1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1
        );
        GradeDistributionDto second = GpaCalculator.fromCounts(
            0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0
        );

        GradeDistributionDto total = GpaCalculator.sum(List.of(first, second));

        assertEquals(4, total.total());
        assertEquals(1, total.aPlus());
        assertEquals(1, total.b());
        assertEquals(1, total.f());
        assertEquals(1, total.w());
        assertEquals(7.0 / 3.0, total.gpa(), 0.0001);
    }
}
