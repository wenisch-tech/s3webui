Flyway migrations for data and structure fix-ups.

The schema itself is created by Hibernate (`spring.jpa.hibernate.ddl-auto=update`), and Flyway is
baselined (`baseline-on-migrate=true`, `baseline-version=0`). Flyway runs *before* Hibernate, so do
not add a from-scratch `V1__init.sql` here — it would race the generated schema. Default rows are
seeded in `config/DataInitializer`. Add `V<n>__snake_case_description.sql` files here only for
later data fix-ups or column changes that Hibernate cannot infer.
