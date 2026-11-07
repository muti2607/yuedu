package com.example.yuedu.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.yuedu.data.model.Book
import com.example.yuedu.data.model.Chapter

@Database(entities = [Book::class, Chapter::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao

    companion object {
        private const val DB_NAME = "yuedu_database"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * books 表的完整建表语句，必须与 Book 实体的字段顺序、类型、
         * 非空约束**逐字一致**，否则 Room 校验会失败。
         *
         * 注意：status 列没有 DEFAULT —— Kotlin 的 `= ""` 默认值不会生成
         * SQL DEFAULT，Room 期望的就是纯 `TEXT NOT NULL`。
         */
        private const val CREATE_BOOKS_V3 = """
            CREATE TABLE IF NOT EXISTS `books` (
                `url` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `author` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `coverUrl` TEXT NOT NULL,
                `category` TEXT NOT NULL,
                `lastChapterTitle` TEXT NOT NULL,
                `lastUpdateTime` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `isInBookshelf` INTEGER NOT NULL,
                `lastReadChapterIndex` INTEGER NOT NULL,
                `lastReadPosition` INTEGER NOT NULL,
                `addTime` INTEGER NOT NULL,
                PRIMARY KEY(`url`)
            )
        """

        /**
         * 旧表（v1）复制到新表。
         *
         * status 是新列且声明为 NOT NULL 无默认值，
         * 必须在这里显式补 ''，否则 INSERT 会违反 NOT NULL 约束、
         * 迁移直接抛异常 → App 启动崩溃。
         */
        private const val COPY_BOOKS_V1 = """
            INSERT INTO `books` (
                `url`,`title`,`author`,`description`,`coverUrl`,`category`,
                `lastChapterTitle`,`lastUpdateTime`,`status`,`isInBookshelf`,
                `lastReadChapterIndex`,`lastReadPosition`,`addTime`
            )
            SELECT
                `url`,`title`,`author`,`description`,`coverUrl`,`category`,
                `lastChapterTitle`,`lastUpdateTime`,'' AS `status`,`isInBookshelf`,
                `lastReadChapterIndex`,`lastReadPosition`,`addTime`
            FROM `books_old`
        """

        /**
         * v1 → v2：Book 新增 status。
         *
         * 早期版本用 `ALTER TABLE ... ADD COLUMN status ... DEFAULT ''` 实现，
         * 但实体的期望是「无 DEFAULT」，两者不一致 → Room 校验抛异常，
         * 表现为 App 启动即崩溃。这里统一改为「重建表」，
         * 保证迁移结果与实体声明严格一致。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `books` RENAME TO `books_old`")
                db.execSQL(CREATE_BOOKS_V3)
                db.execSQL(COPY_BOOKS_V1)
                db.execSQL("DROP TABLE `books_old`")
            }
        }

        /**
         * v2 → v3 修复迁移。
         *
         * v2 版本曾用 ADD COLUMN 方式加列，导致表里 status 带 DEFAULT，
         * 与实体不匹配，用户升级后会反复崩溃。
         * 这里同样用重建表的方式把结构纠正过来。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `books` RENAME TO `books_old`")
                db.execSQL(CREATE_BOOKS_V3)
                // v2 表已经有 status 列，一并复制过来
                db.execSQL(
                    """
                    INSERT INTO `books` (
                        `url`,`title`,`author`,`description`,`coverUrl`,`category`,
                        `lastChapterTitle`,`lastUpdateTime`,`status`,`isInBookshelf`,
                        `lastReadChapterIndex`,`lastReadPosition`,`addTime`
                    )
                    SELECT
                        `url`,`title`,`author`,`description`,`coverUrl`,`category`,
                        `lastChapterTitle`,`lastUpdateTime`,`status`,`isInBookshelf`,
                        `lastReadChapterIndex`,`lastReadPosition`,`addTime`
                    FROM `books_old`
                    """
                )
                db.execSQL("DROP TABLE `books_old`")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    // 保底：万一遇到未覆盖的版本或已损坏的库，
                    // 宁可清空数据也不要让 App 直接崩溃（用户至少还能继续用）
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
