import copy
import json
import re
from pathlib import Path
import unittest
import uuid

from scripts.validate_terraform_plan import cloud_config, cloud_manifest, validate_bootstrap, validate_environment

FIXTURES = Path(__file__).parent / "fixtures/terraform"


def row(plan, address):
    return next(resource["change"]["after"] for resource in plan["resource_changes"] if resource["address"] == address)


class TerraformPlanControlsTests(unittest.TestCase):
    def setUp(self):
        self.plan = json.loads((FIXTURES / "environment-plan.json").read_text())

    def test_trimmed_real_mock_plan_passes(self):
        self.assertEqual("PASS", validate_environment(self.plan, "staging")["network"])

    def test_public_examples_cover_declared_inputs_without_ephemeral_credentials(self):
        for name in ["staging","production"]:
            directory=Path(__file__).resolve().parents[1]/"infra/yandex/environments"/name
            variables=(directory/"variables.tf").read_text()
            declared=set(re.findall(r'^variable "([a-z_]+)"',variables,re.M))
            example=(directory/"terraform.tfvars.example").read_text()
            supplied=set(re.findall(r'^([a-z_]+)\s*=',example,re.M))
            omitted={"migration_role_password","application_role_password","default_zone","application_instances","worker_instances"}
            self.assertEqual(declared-omitted,supplied)
            self.assertRegex(example,r'(?m)^runtime_enabled\s*=\s*false$')
            self.assertRegex(example,r'(?m)^migration_evidence\s*=\s*null$')

    def test_foundation_has_no_runtime_or_public_application_ingress(self):
        plan=copy.deepcopy(self.plan)
        plan["resource_changes"]=[r for r in plan["resource_changes"] if not r["address"].startswith("module.workload[")]
        self.assertEqual("NOT_CREATED_BEFORE_MIGRATION",validate_environment(plan,"staging",runtime_expected=False)["runtime"])
        with self.assertRaisesRegex(ValueError,"Foundation"):
            validate_environment(self.plan,"staging",runtime_expected=False)

    def test_network_authority_mutations_are_rejected(self):
        changes = [
            ('module.network.yandex_vpc_subnet.private["ru-central1-a"]', "route_table_id", None),
            ('module.network.yandex_vpc_security_group_rule.alb_to_application["8080"]', "security_group_id", "wrong-group"),
            ('module.network.yandex_vpc_security_group_rule.application_from_alb["8088"]', "port", 22),
            ('module.network.yandex_vpc_security_group_rule.public_https', "port", 80),
            ('module.network.yandex_vpc_security_group_rule.https_egress["worker"]', "protocol", "ANY"),
            ('module.network.yandex_vpc_security_group_rule.database_ingress["application"]', "security_group_id", "public-group"),
            ('module.network.yandex_vpc_security_group_rule.database_egress["migration"]', "port", 5432),
        ]
        for address, key, value in changes:
            with self.subTest(address=address, key=key):
                plan = copy.deepcopy(self.plan)
                row(plan, address)[key] = value
                with self.assertRaises(ValueError):
                    validate_environment(plan, "staging")

    def test_database_and_provider_secret_persistence_mutations_are_rejected(self):
        # A fresh test-only value exercises persistence rejection without a
        # reusable credential-shaped literal in the repository.
        marker = str(uuid.uuid4())
        mutations = [
            lambda p: row(p, "module.database.yandex_mdb_postgresql_cluster.this")["host"][0].update(assign_public_ip=True),
            lambda p: row(p, "module.database.yandex_mdb_postgresql_cluster.this")["config"][0].update(backup_retain_period_days=1),
            lambda p: row(p, "module.database.yandex_mdb_postgresql_cluster.this")["config"][0].update(version="18"),
            lambda p: row(p, "module.database.yandex_mdb_postgresql_database.this").update(extension=[{"name": "btree_gist"}]),
            lambda p: row(p, "module.database.yandex_mdb_postgresql_database.this").update(extension=[]),
            lambda p: row(p, "module.database.yandex_mdb_postgresql_database.this").update(extension=[{"name": "btree_gist"}, {"name": "hstore"}]),
            lambda p: row(p, "module.database.yandex_mdb_postgresql_user.application").update(password=marker),
            lambda p: row(p, "module.database.yandex_mdb_postgresql_user.migration").update(password_wo=marker),
            lambda p: row(p, "module.database.yandex_mdb_postgresql_user.migration").update(password_wo_version=0),
            lambda p: p["provider_schemas"]["registry.terraform.io/yandex-cloud/yandex"]["resource_schemas"]["yandex_mdb_postgresql_user"]["block"]["attributes"]["password_wo"].update(write_only=False),
        ]
        for mutate in mutations:
            plan = copy.deepcopy(self.plan)
            mutate(plan)
            with self.assertRaises(ValueError):
                validate_environment(plan, "staging")

    def test_runtime_public_access_role_and_health_mutations_are_rejected(self):
        address = 'module.workload[0].yandex_compute_instance_group.runtime["application"]'
        mutations = [
            lambda v: v["instance_template"][0]["network_interface"][0].update(nat=True),
            lambda v: v["instance_template"][0].update(service_account_id="migration-identity"),
            lambda v: v["deploy_policy"][0].update(max_unavailable=3),
            lambda v: v["health_check"][0]["http_options"][0].update(path="/actuator/health/liveness"),
        ]
        for mutate in mutations:
            plan = copy.deepcopy(self.plan)
            mutate(row(plan, address))
            with self.assertRaises(ValueError):
                validate_environment(plan, "staging")
        for key, value in [("MARKETOPS_PRICE_WRITE_WORKER_ENABLED", "true"), ("SPRING_DATASOURCE_URL", "jdbc:postgresql://database/marketops?sslmode=disable")]:
            plan = copy.deepcopy(self.plan)
            instance = row(plan, address)
            manifest = cloud_manifest(instance)
            manifest["environment"][key] = value
            config = cloud_config(instance)
            next(f for f in config["write_files"] if f["path"] == "/etc/marketops/runtime.json")["content"] = json.dumps(manifest)
            instance["instance_template"][0]["metadata"]["user-data"] = "#cloud-config\n" + json.dumps(config)
            with self.assertRaises(ValueError):
                validate_environment(plan, "staging")

    def test_public_route_and_raw_custody_mutations_are_rejected(self):
        mutations = [
            lambda p: row(p, "module.workload[0].yandex_alb_load_balancer.this")["listener"][0]["endpoint"][0].update(ports=[80]),
            lambda p: row(p, "module.workload[0].yandex_alb_virtual_host.this")["route"][0]["http_route"][0]["http_match"][0]["path"][0].update(prefix="/api/"),
            lambda p: row(p, "module.workload[0].yandex_alb_virtual_host.this")["route"].reverse(),
            lambda p: row(p, "module.object_storage.yandex_storage_bucket.evidence")["anonymous_access_flags"][0].update(read=True),
            lambda p: row(p, "module.object_storage.yandex_storage_bucket.evidence").update(force_destroy=True),
            lambda p: row(p, "module.object_storage.yandex_storage_bucket.evidence")["object_lock_configuration"][0]["rule"][0]["default_retention"][0].update(mode="GOVERNANCE"),
            lambda p: row(p, 'module.workload_identity.yandex_resourcemanager_folder_iam_member.logs["application"]').update(role="editor"),
        ]
        for mutate in mutations:
            plan = copy.deepcopy(self.plan)
            mutate(plan)
            with self.assertRaises(ValueError):
                validate_environment(plan, "staging")

    def test_telemetry_runtime_and_identity_mutations_are_rejected(self):
        address = 'module.workload[0].yandex_compute_instance_group.runtime["application"]'
        def file(config, suffix):
            return next(f for f in config["write_files"] if f["path"].endswith(suffix))
        mutations = [
            lambda c: c.update(runcmd=[]),
            lambda c: file(c, "telemetry.py").update(permissions="0777"),
            lambda c: file(c, "telemetry.py").update(content="cHJpbnQoJ2JhZCcp"),
            lambda c: file(c, "telemetry.service").update(content="[Service]\nUser=root"),
            lambda c: file(c, "telemetry.timer").update(content="[Timer]\nOnBootSec=3600"),
            lambda c: file(c, "telemetry.json").update(content=json.dumps({"folder_id":"other", "environment":"staging", "role":"application"})),
            lambda c: c["write_files"].append(copy.deepcopy(file(c, "telemetry.py"))),
            lambda c: c.update(write_files=[f for f in c["write_files"] if not f["path"].endswith("telemetry.py")]),
        ]
        for mutate in mutations:
            plan=copy.deepcopy(self.plan)
            instance=row(plan,address)
            config=cloud_config(instance)
            mutate(config)
            instance["instance_template"][0]["metadata"]["user-data"]="#cloud-config\n"+json.dumps(config)
            with self.assertRaisesRegex(ValueError,"Telemetry|Duplicate"):
                validate_environment(plan,"staging")
        grant='module.workload_identity.yandex_resourcemanager_folder_iam_member.telemetry["application"]'
        for field,value in [("member","serviceAccount:wrong"),("folder_id","other"),("role","monitoring.admin")]:
            plan=copy.deepcopy(self.plan)
            row(plan,grant)[field]=value
            with self.assertRaisesRegex(ValueError,"Telemetry"):
                validate_environment(plan,"staging")

    def test_state_custody_controls_reject_mutation(self):
        fixture = json.loads((FIXTURES / "bootstrap-plan.json").read_text())
        self.assertEqual("PASS", validate_bootstrap(fixture)["state_custody"])
        changes = [
            lambda p: row(p, "yandex_storage_bucket.state")["versioning"][0].update(enabled=False),
            lambda p: row(p, "yandex_storage_bucket.state")["anonymous_access_flags"][0].update(read=True),
            lambda p: row(p, "yandex_ydb_database_serverless.locks").update(deletion_protection=False),
        ]
        for mutate in changes:
            plan = copy.deepcopy(fixture)
            mutate(plan)
            with self.assertRaises(ValueError):
                validate_bootstrap(plan)


if __name__ == "__main__":
    unittest.main()
