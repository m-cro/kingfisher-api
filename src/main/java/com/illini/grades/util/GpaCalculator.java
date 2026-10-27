package com.illini.grades.util;

import com.illini.grades.dto.GradeDistributionDto;
import java.util.List;

/** A GPA is undefined (null) when its denominator contains no grades. */
public class GpaCalculator {

    public static Double computeGpa(int aPlus, int a, int aMinus,
                                    int bPlus, int b, int bMinus,
                                    int cPlus, int c, int cMinus,
                                    int dPlus, int d, int dMinus,
                                    int f, int w) {
        double points = 4.0 * (aPlus + a) + 3.67 * aMinus
                      + 3.33 * bPlus + 3.0 * b + 2.67 * bMinus
                      + 2.33 * cPlus + 2.0 * c + 1.67 * cMinus
                      + 1.33 * dPlus + 1.0 * d + 0.67 * dMinus
                      + 0.0 * f;
        int credits = aPlus + a + aMinus + bPlus + b + bMinus
                    + cPlus + c + cMinus + dPlus + d + dMinus + f;
        if (credits == 0) return null;
        return points / credits;
    }

    public static Double computeGpaIncludingW(int aPlus, int a, int aMinus,
                                              int bPlus, int b, int bMinus,
                                              int cPlus, int c, int cMinus,
                                              int dPlus, int d, int dMinus,
                                              int f, int w) {
        double points = 4.0 * (aPlus + a) + 3.67 * aMinus
                      + 3.33 * bPlus + 3.0 * b + 2.67 * bMinus
                      + 2.33 * cPlus + 2.0 * c + 1.67 * cMinus
                      + 1.33 * dPlus + 1.0 * d + 0.67 * dMinus
                      + 0.0 * f;
        int credits = aPlus + a + aMinus + bPlus + b + bMinus
                    + cPlus + c + cMinus + dPlus + d + dMinus + f + w;
        if (credits == 0) return null;
        return points / credits;
    }

    public static GradeDistributionDto fromCounts(int aPlus, int a, int aMinus,
                                                  int bPlus, int b, int bMinus,
                                                  int cPlus, int c, int cMinus,
                                                  int dPlus, int d, int dMinus,
                                                  int f, int w) {
        int total = aPlus + a + aMinus + bPlus + b + bMinus
                  + cPlus + c + cMinus + dPlus + d + dMinus + f + w;
        Double gpa = computeGpa(aPlus, a, aMinus, bPlus, b, bMinus, cPlus, c, cMinus, dPlus, d, dMinus, f, w);
        Double gpaIncludingW = computeGpaIncludingW(aPlus, a, aMinus, bPlus, b, bMinus, cPlus, c, cMinus, dPlus, d, dMinus, f, w);

        return new GradeDistributionDto(
            aPlus, a, aMinus, bPlus, b, bMinus, cPlus, c, cMinus, dPlus, d, dMinus, f, w,
            total, gpa, gpaIncludingW
        );
    }

    public static GradeDistributionDto sum(List<GradeDistributionDto> distributions) {
        int aPlus = 0, a = 0, aMinus = 0, bPlus = 0, b = 0, bMinus = 0;
        int cPlus = 0, c = 0, cMinus = 0, dPlus = 0, d = 0, dMinus = 0;
        int f = 0, w = 0;

        for (GradeDistributionDto dist : distributions) {
            aPlus += dist.aPlus();
            a += dist.a();
            aMinus += dist.aMinus();
            bPlus += dist.bPlus();
            b += dist.b();
            bMinus += dist.bMinus();
            cPlus += dist.cPlus();
            c += dist.c();
            cMinus += dist.cMinus();
            dPlus += dist.dPlus();
            d += dist.d();
            dMinus += dist.dMinus();
            f += dist.f();
            w += dist.w();
        }

        return fromCounts(aPlus, a, aMinus, bPlus, b, bMinus, cPlus, c, cMinus, dPlus, d, dMinus, f, w);
    }
}
