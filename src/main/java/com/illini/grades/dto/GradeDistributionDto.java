package com.illini.grades.dto;

public record GradeDistributionDto(
    int aPlus,
    int a,
    int aMinus,
    int bPlus,
    int b,
    int bMinus,
    int cPlus,
    int c,
    int cMinus,
    int dPlus,
    int d,
    int dMinus,
    int f,
    int w,
    int total,
    Double gpa,
    Double gpaIncludingW
) {}
