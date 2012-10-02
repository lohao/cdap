//
// Stream Model
//

define([], function () {
	return Em.Object.extend({
		href: function () {
			return '#/streams/' + this.get('id');
		}.property().cacheable()
	});
});