-- Install the btree_gist extension into the public schema.
--
-- Effective-dated association tables prevent overlapping active intervals with
-- an exclusion constraint that combines scalar equality (uuid and text columns)
-- with range overlap. GiST supports that combination only when the scalar
-- operator classes from btree_gist are available.
--
-- btree_gist is a trusted extension, so the migrating role can install it with
-- its database CREATE privilege and no superuser is involved. The target schema
-- is named explicitly so the object location does not depend on the migrating
-- role's search path.
--
-- Installation is strict: if the extension is already present, this migration
-- fails and surfaces a database that was initialised outside the migration
-- history, exactly as the schema-creation migration does for schemas.

CREATE EXTENSION btree_gist WITH SCHEMA public;
