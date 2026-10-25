.class public Lloops/TestIrreducibleLoop;
.super Ljava/lang/Object;

.method public static test(I)I
    .registers 3

    if-lez p0, :cond_loop2

    :loop1
    add-int/lit8 p0, p0, 0x1
    if-lez p0, :loop2
    goto :loop1

    :cond_loop2
    :loop2
    add-int/lit8 p0, p0, -0x1
    if-lez p0, :loop1
    goto :loop2
.end method
