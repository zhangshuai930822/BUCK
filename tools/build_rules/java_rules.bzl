def _add_immutables(**kwargs):
    kwargs['deps'] = depset(kwargs.get('deps', [])) + [
        '//src/com/facebook/buck/util/immutables:immutables',
        '//third-party/java/errorprone:error-prone-annotations',
        '//third-party/java/immutables:immutables',
        '//third-party/java/guava:guava',
        '//third-party/java/jsr:jsr305',
    ]
    kwargs['exported_deps'] = depset(kwargs.get('exported_deps', [])) + [
        '//third-party/java/immutables:immutables',
    ]
    kwargs['plugins'] = depset(kwargs.get('plugins', [])) + [
        '//third-party/java/immutables:processor'
    ]
    return kwargs

def java_immutables_library(name, **kwargs):
    return native.java_library(
      name=name,
      **_add_immutables(**kwargs))

def _shallow_dict_copy_without_key(table, key_to_omit):
  """Returns a shallow copy of dict with key_to_omit omitted."""
  return {key: table[key] for key in table if key != key_to_omit}

def java_test(
    name,
    vm_args=None,
    labels=None,
    run_test_separately=False,
    has_immutable_types=False,
    # deps, provided_deps and plugins are handled in kwargs so that immutables can be handled there
    **kwargs
    ):
  extra_labels = ['run_as_bundle']
  if run_test_separately:
    extra_labels.append('serialize')

  if has_immutable_types:
    kwargs = _add_immutables(**kwargs)

  if 'deps' in kwargs:
    deps = kwargs['deps']
    kwargs = _shallow_dict_copy_without_key(kwargs, 'deps')
  else:
    deps = []

  if 'env' in kwargs:
    env = kwargs['env']
    kwargs = _shallow_dict_copy_without_key(kwargs, 'env')
  else:
    env = {}

  if '//src/com/facebook/buck/step/external:external' in deps:
    env['EXTERNAL_STEP_RUNNER_JAR_FOR_BUCK_TEST'] = (
      '$(location //src/com/facebook/buck/step/external:executor)')

  native.java_test(
    name=name,
    deps=deps + [
      # When actually running Buck, the launcher script loads the bootstrapper,
      # and the bootstrapper loads the rest of Buck. For unit tests, which don't
      # run Buck, we have to add a direct dependency on the bootstrapper in case
      # they exercise code that uses it.
      '//src/com/facebook/buck/cli/bootstrapper:bootstrapper_lib',
    ],
    vm_args=[
      # Add -XX:-UseSplitVerifier by default to work around:
      # http://arihantwin.blogspot.com/2012/08/getting-error-illegal-local-variable.html
      '-XX:-UseSplitVerifier',

      # Don't use the system-installed JNA; extract it from the local jar.
      '-Djna.nosys=true',

      # Add -Dsun.zip.disableMemoryMapping=true to work around a JDK issue
      # related to modifying JAR/ZIP files that have been loaded into memory:
      #
      # http://bugs.sun.com/view_bug.do?bug_id=7129299
      #
      # This has been observed to cause a problem in integration tests such as
      # CachedTestIntegrationTest where `buck build //:test` is run repeatedly
      # such that a corresponding `test.jar` file is overwritten several times.
      # The CompiledClassFileFinder in JavaTestRule creates a java.util.zip.ZipFile
      # to enumerate the zip entries in order to find the set of .class files
      # in `test.jar`. This interleaving of reads and writes appears to match
      # the conditions to trigger the issue reported on bugs.sun.com.
      #
      # Currently, we do not set this flag in bin/buck_common, as Buck does not
      # normally modify the contents of buck-out after they are loaded into
      # memory. However, we may need to use this flag when running buckd where
      # references to zip files may be long-lived.
      #
      # Finally, note that when you specify this flag,
      # `System.getProperty("sun.zip.disableMemoryMapping")` will return `null`
      # even though you have specified the flag correctly. Apparently sun.misc.VM
      # (http://www.docjar.com/html/api/sun/misc/VM.java.html) saves the property
      # internally, but removes it from the set of system properties that are
      # publicly accessible.
      '-Dsun.zip.disableMemoryMapping=true',
    ] + (vm_args or []),
    env=env,
    run_test_separately=run_test_separately,
    labels = labels + extra_labels,
    **kwargs
  )

def standard_java_test(
    name,
    run_test_separately = False,
    vm_args = None,
    fork_mode = 'none',
    labels = None,
    with_test_data = True,
    **kwargs
):
    if vm_args == None:
        vm_args = ['-Xmx256M']

    test_srcs = native.glob(["*Test.java"])

    if len(test_srcs) > 0:
        java_test(
          name = name,
          srcs = test_srcs,
          resources = native.glob(['testdata/**']) if with_test_data else [],
          vm_args = vm_args,
          run_test_separately = run_test_separately,
          fork_mode = fork_mode,
          labels = labels or [],
          **kwargs
        )

def _add_pf4j_plugin_framework(**kwargs):
    kwargs["deps"] = list(depset(kwargs.get("deps", [])).union([
        "//third-party/java/pf4j:pf4j",
    ]))
    kwargs["plugins"] = list(depset(kwargs.get("plugins", [])).union([
        "//third-party/java/pf4j:processor",
    ]))
    kwargs["annotation_processor_params"] = list(depset(kwargs.get("annotation_processor_params", [])).union([
        "pf4j.storageClassName=org.pf4j.processor.ServiceProviderExtensionStorage",
    ]))
    return kwargs

def java_library_with_plugins(name, **kwargs):
    """
    `java_library` that can contain plugins based on pf4j framework
    """

    kwargs_with_immutables = _add_immutables(**kwargs)
    return native.java_library(
      name=name,
      **_add_pf4j_plugin_framework(**kwargs_with_immutables))
