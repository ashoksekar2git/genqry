-- 1. School Details (sch_dtl)
CREATE TABLE sch_dtl (
                         sch_id SERIAL PRIMARY KEY,
                         sch_nm VARCHAR(100) NOT NULL,
                         sch_loc TEXT
);

-- 2. Departments Details (dept_dtl)
-- Foreign Key: Linked to sch_id
CREATE TABLE dept_dtl (
                          dept_id SERIAL PRIMARY KEY,
                          sch_id INT REFERENCES sch_dtl(sch_id) ON DELETE CASCADE,
                          dept_nm VARCHAR(100) NOT NULL,
                          dept_head VARCHAR(100)
);

-- 3. Students Details (stu_dtl)
-- Foreign Key: Linked to dept_id
CREATE TABLE stu_dtl (
                         stu_id SERIAL PRIMARY KEY,
                         dept_id INT REFERENCES dept_dtl(dept_id) ON DELETE SET NULL,
                         stu_fname VARCHAR(50) NOT NULL,
                         stu_lname VARCHAR(50) NOT NULL,
                         stu_dob DATE
);

-- 4. Courses Details (crs_dtl)
-- Foreign Key: Linked to dept_id
CREATE TABLE crs_dtl (
                         crs_id SERIAL PRIMARY KEY,
                         dept_id INT REFERENCES dept_dtl(dept_id) ON DELETE CASCADE,
                         crs_nm VARCHAR(100) NOT NULL,
                         crs_crdt INT CHECK (crs_crdt > 0)
);
