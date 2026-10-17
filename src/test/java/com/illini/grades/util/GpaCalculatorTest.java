package com.illini.grades.util;

import com.illini.grades.dto.GradeDistributionDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void emptyGradeCountsHaveZeroGpa() {
        assertEquals(0.0, GpaCalculator.computeGpa(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        ));
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
