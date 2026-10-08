package com.game.match.rating;

/**
 * {@link RatingStore} 在 H2（MySQL 兼容模式）上的全部行为用例（缺省执行；用例本身在 {@link RatingStoreCases}）。每个用例一个全新的内存库，
 * 两张表用 pbmysql 生成的同一份 DDL 建。H2 与 MySQL 在错误码上的差异（主键冲突 23505 对 1062）由 {@link RatingSqlErrors} 按标准 SQLState 兜住；
 * 真的死锁、锁等待与并发入账只在真 MySQL 上才有，见 {@link RatingStoreSqlTest}。
 */
class RatingStoreTest extends RatingStoreCases {

    @Override
    protected RatingTestDatabase openDatabase() {
        return RatingTestDatabase.h2();
    }

    @Override
    protected void closeDatabase(RatingTestDatabase database) {
        database.close();
    }
}
