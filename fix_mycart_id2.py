content = open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyViewModel.kt', 'r', encoding='utf-8').read()

# In readLiveNodes, derive myCartId from nodeRepository directly rather than relying on the collected value
old = '    private val _myCartId = MutableStateFlow(ConvoySimulation.MY_CART_ID)'
new = '    private val _myCartId = MutableStateFlow(ConvoySimulation.MY_CART_ID)\n\n    private fun resolveMyCartId(): String {\n        val num = nodeRepository.myNodeInfo.value?.myNodeNum\n        return if (num != null) "!%08x".format(num) else _myCartId.value\n    }'

print('Found:', old in content)
result = content.replace(old, new)

# Also update the tick to use resolveMyCartId()
old2 = '        val state = ConvoyEngine.compute(\n            nodes = nodes,\n            myCartId = _myCartId.value,'
new2 = '        val state = ConvoyEngine.compute(\n            nodes = nodes,\n            myCartId = resolveMyCartId(),'

print('Found2:', old2 in result)
result = result.replace(old2, new2)

open('app/src/main/java/com/geeksville/mesh/convoy/ConvoyViewModel.kt', 'w', encoding='utf-8').write(result)
print('Done')
